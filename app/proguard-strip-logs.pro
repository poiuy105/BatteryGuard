# Release 专用：剥离调试日志调用。
# -assumenosideeffects 告知 R8 这些方法无副作用、返回值未被使用即可安全删除，
# 连带移除为日志拼接而生成的字符串常量与 StringBuilder —— 同时减小体积与运行时开销。
# 仅剥离 d/i/v；保留 e/w 用于线上错误诊断。
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int i(...);
    public static int v(...);
}
