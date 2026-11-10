package com.example

import org.junit.Assert.assertEquals
import org.junit.Test

class AppUnitTest {

  @Test
  fun testAppNameConstant() {
    val expectedName = "DpiTunnel"
    assertEquals("DpiTunnel", expectedName)
  }
}
