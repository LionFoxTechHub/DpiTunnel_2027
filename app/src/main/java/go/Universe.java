package go;

public abstract class Universe {
    private Universe() {}

    public static void touch() {}

    private static native void _init();

    static {
        Seq.touch();
        _init();
    }

    static final class proxyerror extends Exception implements Seq.Proxy, error {
        public final int refnum;

        @Override
        public final int incRefnum() {
            Seq.incGoRef(refnum, this);
            return refnum;
        }

        proxyerror(int refnum) {
            this.refnum = refnum;
            Seq.trackGoRef(refnum, this);
        }

        @Override
        public String getMessage() {
            return error();
        }

        @Override
        public native String error();
    }
}
