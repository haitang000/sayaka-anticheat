package cn.haitang.anticheat.velocity.boot;

import java.io.IOException;
import java.nio.file.Path;

/** 内核向宿主请求热替换的回调；实现方为宿主，跨内核代次保持稳定。 */
public interface HotReloader {

    /**
     * 校验暂存 jar 并调度热替换。同步返回即表示已受理；
     * 替换本身在数秒后异步执行，期间调用方（面板 HTTP 线程）
     * 有时间把响应发回浏览器。
     *
     * @throws IOException 暂存文件缺失/不可读，或已有一次热替换在进行
     */
    void scheduleApply(Path stagedJar, String stagedVersion) throws IOException;
}
