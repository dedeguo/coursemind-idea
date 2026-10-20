package top.chende.coursemind.auth;

import org.jetbrains.annotations.NotNull;
import top.chende.coursemind.CourseMindException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * DPAPI（CurrentUser 作用域）加解密，与 VS Code 扩展使用同一加密域。
 *
 * <p>实现方式与扩展一致：调用系统内置 Windows PowerShell 的
 * {@code System.Security.Cryptography.ProtectedData}，明文/密文只经 stdin/stdout，
 * 不出现在命令行参数中；不使用 JNA，避免新增运行时依赖。
 */
public final class Dpapi {

    private static final long TIMEOUT_SECONDS = 15;

    private static final String PROTECT_SCRIPT = String.join("\r\n",
            "$ErrorActionPreference = 'Stop'",
            "[Console]::InputEncoding = [System.Text.Encoding]::UTF8",
            "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8",
            "Add-Type -AssemblyName System.Security",
            "$inputText = [Console]::In.ReadToEnd().Trim()",
            "$plainBytes = [Convert]::FromBase64String($inputText)",
            "$cipherBytes = [System.Security.Cryptography.ProtectedData]::Protect($plainBytes, $null, "
                    + "[System.Security.Cryptography.DataProtectionScope]::CurrentUser)",
            "[Console]::Out.Write([Convert]::ToBase64String($cipherBytes))");

    private static final String UNPROTECT_SCRIPT = String.join("\r\n",
            "$ErrorActionPreference = 'Stop'",
            "[Console]::InputEncoding = [System.Text.Encoding]::UTF8",
            "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8",
            "Add-Type -AssemblyName System.Security",
            "$inputText = [Console]::In.ReadToEnd().Trim()",
            "$cipherBytes = [Convert]::FromBase64String($inputText)",
            "$plainBytes = [System.Security.Cryptography.ProtectedData]::Unprotect($cipherBytes, $null, "
                    + "[System.Security.Cryptography.DataProtectionScope]::CurrentUser)",
            "[Console]::Out.Write([Convert]::ToBase64String($plainBytes))");

    private Dpapi() {
    }

    public static boolean isSupported() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    public static byte @NotNull [] protect(byte @NotNull [] plaintext) {
        return run(PROTECT_SCRIPT, plaintext);
    }

    public static byte @NotNull [] unprotect(byte @NotNull [] ciphertext) {
        return run(UNPROTECT_SCRIPT, ciphertext);
    }

    private static byte @NotNull [] run(String script, byte @NotNull [] input) {
        if (!isSupported()) {
            throw new CourseMindException("当前系统不支持 Windows 凭据保护（DPAPI），无法读写共享登录状态");
        }
        Path powershell = powershellPath();
        Process process;
        try {
            process = new ProcessBuilder(powershell.toString(), "-NoProfile", "-NonInteractive", "-Command", script)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException e) {
            throw new CourseMindException("无法启动凭据保护进程，请检查系统环境后重试", e);
        }

        Thread writer = new Thread(() -> {
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(Base64.getEncoder().encode(input));
                stdin.flush();
            } catch (IOException ignored) {
                // 进程提前退出时由读取端统一报错
            }
        }, "coursemind-dpapi-writer");
        writer.setDaemon(true);
        writer.start();

        byte[] stdout;
        try {
            stdout = readAll(process.getInputStream());
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new CourseMindException("凭据保护操作超时，请重试");
            }
        } catch (IOException e) {
            throw new CourseMindException("读取凭据保护结果失败", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CourseMindException("凭据保护操作被中断", e);
        }

        if (process.exitValue() != 0) {
            throw new CourseMindException("凭据保护失败：无法使用当前 Windows 用户解密共享登录状态");
        }
        String base64 = new String(stdout, StandardCharsets.UTF_8).trim();
        if (base64.isEmpty()) {
            throw new CourseMindException("凭据保护进程返回了空结果");
        }
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new CourseMindException("凭据保护进程返回了无法识别的结果", e);
        }
    }

    private static byte @NotNull [] readAll(InputStream stream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = stream.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private static @NotNull Path powershellPath() {
        String systemRoot = System.getenv("SystemRoot");
        Path base = (systemRoot == null || systemRoot.isBlank()) ? Paths.get("C:\\Windows") : Paths.get(systemRoot);
        Path preferred = base.resolve("System32").resolve("WindowsPowerShell").resolve("v1.0").resolve("powershell.exe");
        if (Files.isRegularFile(preferred)) {
            return preferred;
        }
        return Paths.get("powershell.exe");
    }
}
