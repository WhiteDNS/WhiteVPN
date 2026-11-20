/* SPDX-License-Identifier: Apache-2.0
 * Native ABI derived from AmneziaWG Android fb7575a54e35d9a19cb1f64cdd90bf7075163b55. */
package org.amnezia.awg;
public final class GoBackend {
    private GoBackend() {}
    public interface SocketProtector { boolean protect(int fd); }
    public static volatile SocketProtector protector;
    public static boolean protectSocket(int fd) { SocketProtector p = protector; return p != null && p.protect(fd); }
    public static native String awgGetConfig(int handle);
    public static native int awgGetSocketV4(int handle);
    public static native int awgGetSocketV6(int handle);
    public static native void awgTurnOff(int handle);
    public static native int awgTurnOn(String ifName, int tunFd, String settings);
    public static native String awgVersion();
}
