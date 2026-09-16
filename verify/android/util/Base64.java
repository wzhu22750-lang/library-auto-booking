package android.util;
/** JVM 替身：只为实现 android.util.Base64 在桌面 JVM 上的行为（跑的仍是真的 Net/Booker 代码） */
public class Base64 {
    public static final int DEFAULT = 0;
    public static final int NO_WRAP = 2;
    public static byte[] decode(String s, int flags) {
        return java.util.Base64.getMimeDecoder().decode(s);
    }
    public static String encodeToString(byte[] b, int flags) {
        return java.util.Base64.getEncoder().encodeToString(b);
    }
}
