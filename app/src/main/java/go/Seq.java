package go;

import android.content.Context;
import java.io.FileInputStream;
import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.logging.Logger;

/**
 * Process-isolated gomobile Seq runtime shared by libbox (:singbox) and libxray (:xray).
 *
 * Both upstream AARs compile identical Java bindings in package `go`, but each native library
 * exports overlapping `Java_go_Seq_*` C symbols. Each Android worker process (`:singbox` vs
 * `:xray`) therefore loads strictly one native Go shared library (`libbox.so` or `libgojni.so`).
 */
public class Seq {
    private static Logger log = Logger.getLogger("GoSeq");
    private static final int NULL_REFNUM = 41;
    public static final Ref nullRef = new Ref(NULL_REFNUM, null);
    private static final GoRefQueue goRefQueue = new GoRefQueue();
    static final RefTracker tracker = new RefTracker();

    static {
        String libName = resolveProcessNativeLibrary();
        System.loadLibrary(libName);
        init();
        Universe.touch();
    }

    private Seq() {}

    private static String resolveProcessNativeLibrary() {
        String procName = readProcessNameFromProc();
        if (procName.endsWith(":xray")) {
            return "gojni";
        }
        return "box";
    }

    private static String readProcessNameFromProc() {
        try (FileInputStream fis = new FileInputStream("/proc/self/cmdline")) {
            byte[] buf = new byte[256];
            int read = fis.read(buf);
            if (read <= 0) {
                return "";
            }
            int len = 0;
            while (len < read && buf[len] != 0) {
                len++;
            }
            return new String(buf, 0, len, StandardCharsets.UTF_8).trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static void setContext(Context context) {
        setContext((Object) context);
    }

    private static native void init();

    public static void touch() {}

    static native void setContext(Object context);

    public static void incRefnum(int refnum) {
        tracker.incRefnum(refnum);
    }

    public static int incRef(Object obj) {
        return tracker.inc(obj);
    }

    public static int incGoObjectRef(GoObject obj) {
        return obj.incRefnum();
    }

    public static void trackGoRef(int refnum, GoObject obj) {
        if (refnum > 0) {
            throw new RuntimeException("trackGoRef called with Java refnum " + refnum);
        }
        goRefQueue.track(refnum, obj);
    }

    public static Ref getRef(int refnum) {
        return tracker.get(refnum);
    }

    public static native void incGoRef(int refnum, GoObject obj);

    public static native void destroyRef(int refnum);

    static void decRef(int refnum) {
        tracker.dec(refnum);
    }

    public interface GoObject {
        int incRefnum();
    }

    public interface Proxy extends GoObject {}

    static class GoRef extends PhantomReference<GoObject> {
        final int refnum;

        GoRef(int refnum, GoObject obj, GoRefQueue q) {
            super(obj, q);
            if (refnum > 0) {
                throw new RuntimeException("GoRef instantiated with a Java refnum" + refnum);
            }
            this.refnum = refnum;
        }
    }

    static class GoRefQueue extends ReferenceQueue<GoObject> {
        private final Collection<GoRef> refs = Collections.synchronizedCollection(new HashSet<>());

        void track(int refnum, GoObject obj) {
            refs.add(new GoRef(refnum, obj, this));
        }

        GoRefQueue() {
            Thread finalizer = new Thread(() -> {
                while (true) {
                    try {
                        GoRef ref = (GoRef) remove();
                        refs.remove(ref);
                        Seq.destroyRef(ref.refnum);
                        ref.clear();
                    } catch (InterruptedException ignored) {
                    }
                }
            });
            finalizer.setDaemon(true);
            finalizer.setName("GoRefQueue Finalizer Thread");
            finalizer.start();
        }
    }

    public static final class Ref {
        public final int refnum;
        private int refcnt = 0;
        public final Object obj;

        Ref(int refnum, Object obj) {
            this.refnum = refnum;
            this.obj = obj;
        }

        void inc() {
            if (refcnt == Integer.MAX_VALUE) {
                throw new RuntimeException("refnum " + refnum + " overflow");
            }
            refcnt++;
        }
    }

    static final class RefMap {
        private int next = 0;
        private int live = 0;
        private int[] keys = new int[16];
        private Ref[] objs = new Ref[16];

        RefMap() {}

        Ref get(int key) {
            int i = Arrays.binarySearch(keys, 0, next, key);
            if (i >= 0) {
                return objs[i];
            }
            return null;
        }

        void remove(int key) {
            int i = Arrays.binarySearch(keys, 0, next, key);
            if (i >= 0) {
                if (objs[i] != null) {
                    objs[i] = null;
                    live--;
                }
            }
        }

        void put(int key, Ref obj) {
            if (obj == null) {
                throw new RuntimeException("put a null ref (with key " + key + ")");
            }
            int i = Arrays.binarySearch(keys, 0, next, key);
            if (i >= 0) {
                if (objs[i] == null) {
                    objs[i] = obj;
                    live++;
                }
                if (objs[i] != obj) {
                    throw new RuntimeException("replacing an existing ref (with key" + key + ")");
                }
                return;
            }
            if (next >= keys.length) {
                grow();
                i = Arrays.binarySearch(keys, 0, next, key);
            }
            i = ~i;
            if (i < next) {
                System.arraycopy(keys, i, keys, i + 1, next - i);
                System.arraycopy(objs, i, objs, i + 1, next - i);
            }
            keys[i] = key;
            objs[i] = obj;
            live++;
            next++;
        }

        private void grow() {
            int newCap = 2 * roundPow2(live);
            int[] newKeys;
            Ref[] newObjs;
            if (newCap > keys.length) {
                newKeys = new int[keys.length * 2];
                newObjs = new Ref[objs.length * 2];
            } else {
                newKeys = keys;
                newObjs = objs;
            }
            int j = 0;
            for (int i = 0; i < keys.length; i++) {
                if (objs[i] != null) {
                    newKeys[j] = keys[i];
                    newObjs[j] = objs[i];
                    j++;
                }
            }
            for (int i = j; i < newKeys.length; i++) {
                newKeys[i] = 0;
                newObjs[i] = null;
            }
            keys = newKeys;
            objs = newObjs;
            next = j;
            if (live != next) {
                throw new RuntimeException("bad state: live=" + live + ", next=" + next);
            }
        }

        private static int roundPow2(int x) {
            int p = 1;
            while (p < x) {
                p *= 2;
            }
            return p;
        }
    }

    static final class RefTracker {
        private static final int REF_OFFSET = 42;
        private int next = REF_OFFSET;
        private final RefMap javaObjs = new RefMap();
        private final IdentityHashMap<Object, Integer> javaRefs = new IdentityHashMap<>();

        RefTracker() {}

        synchronized int inc(Object o) {
            if (o == null) {
                return NULL_REFNUM;
            }
            if (o instanceof Proxy) {
                return ((Proxy) o).incRefnum();
            }
            Integer refnumObj = javaRefs.get(o);
            if (refnumObj == null) {
                if (next == Integer.MAX_VALUE) {
                    throw new RuntimeException("createRef overflow for " + o);
                }
                refnumObj = next++;
                javaRefs.put(o, refnumObj);
            }
            int refnum = refnumObj;
            Ref ref = javaObjs.get(refnum);
            if (ref == null) {
                ref = new Ref(refnum, o);
                javaObjs.put(refnum, ref);
            }
            ref.inc();
            return refnum;
        }

        synchronized void incRefnum(int refnum) {
            Ref ref = javaObjs.get(refnum);
            if (ref == null) {
                throw new RuntimeException("referenced Java object is not found: refnum=" + refnum);
            }
            ref.inc();
        }

        synchronized void dec(int refnum) {
            if (refnum <= 0) {
                log.severe("dec request for Go object" + refnum);
                return;
            }
            if (refnum == nullRef.refnum) {
                return;
            }
            Ref ref = javaObjs.get(refnum);
            if (ref == null) {
                throw new RuntimeException("referenced Java object is not found: refnum=" + refnum);
            }
            ref.refcnt--;
            if (ref.refcnt <= 0) {
                javaObjs.remove(refnum);
                javaRefs.remove(ref.obj);
            }
        }

        synchronized Ref get(int refnum) {
            if (refnum < 0) {
                throw new RuntimeException("ref called with Go refnum" + refnum);
            }
            if (refnum == NULL_REFNUM) {
                return nullRef;
            }
            Ref ref = javaObjs.get(refnum);
            if (ref == null) {
                throw new RuntimeException("unknown java Ref:" + refnum);
            }
            return ref;
        }
    }
}
