-keep class com.follow.clash.core.** { *; }
-keep class com.whitedns.vpn.ByeDpiProxy { *; }
-keep class go.** { *; }

# Optional partner engines and the AmneziaWG JNI callback ABI.
-keep class org.amnezia.awg.GoBackend { *; }
-keep interface org.amnezia.awg.GoBackend$SocketProtector { *; }
-keep class psi.** { *; }
-keep class ca.psiphon.** { *; }
-keep class zeddns.** { *; }
-keep class masterdns.** { *; }

-keep class com.jcraft.jsch.** { *; }
-keep class org.bouncycastle.** { *; }

# JSch desktop agents/log adapters/Kerberos and BC LDAP stores are not selected on Android.
# Keep the reflected crypto implementations; ignore only these absent optional APIs.
-dontwarn com.sun.jna.Memory
-dontwarn com.sun.jna.Pointer
-dontwarn com.sun.jna.platform.win32.BaseTSD$ULONG_PTR
-dontwarn com.sun.jna.platform.win32.Kernel32
-dontwarn com.sun.jna.platform.win32.User32
-dontwarn com.sun.jna.platform.win32.WinBase$SECURITY_ATTRIBUTES
-dontwarn com.sun.jna.platform.win32.WinBase
-dontwarn com.sun.jna.platform.win32.WinDef$HWND
-dontwarn com.sun.jna.platform.win32.WinDef$LPARAM
-dontwarn com.sun.jna.platform.win32.WinDef$LRESULT
-dontwarn com.sun.jna.platform.win32.WinDef$WPARAM
-dontwarn com.sun.jna.platform.win32.WinNT$HANDLE
-dontwarn com.sun.jna.platform.win32.WinUser$COPYDATASTRUCT
-dontwarn javax.naming.NamingEnumeration
-dontwarn javax.naming.NamingException
-dontwarn javax.naming.directory.Attribute
-dontwarn javax.naming.directory.Attributes
-dontwarn javax.naming.directory.DirContext
-dontwarn javax.naming.directory.InitialDirContext
-dontwarn javax.naming.directory.SearchControls
-dontwarn javax.naming.directory.SearchResult
-dontwarn org.apache.logging.log4j.Level
-dontwarn org.apache.logging.log4j.LogManager
-dontwarn org.apache.logging.log4j.Logger
-dontwarn org.ietf.jgss.GSSContext
-dontwarn org.ietf.jgss.GSSCredential
-dontwarn org.ietf.jgss.GSSException
-dontwarn org.ietf.jgss.GSSManager
-dontwarn org.ietf.jgss.GSSName
-dontwarn org.ietf.jgss.MessageProp
-dontwarn org.ietf.jgss.Oid
-dontwarn org.newsclub.net.unix.AFUNIXServerSocketChannel
-dontwarn org.newsclub.net.unix.AFUNIXSocketAddress
-dontwarn org.newsclub.net.unix.AFUNIXSocketChannel
-dontwarn org.slf4j.Logger
-dontwarn org.slf4j.LoggerFactory
