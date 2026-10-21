package top.chende.coursemind.auth;

import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;
import top.chende.coursemind.CourseMindException;
import top.chende.coursemind.runtime.AtomicFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 共享登录会话存储（%LOCALAPPDATA%\CourseMind\auth.dat）。
 *
 * <p>加密由 {@link Dpapi} 完成，与 VS Code 扩展使用同一 Windows 用户与同一加密域；
 * 本类不创建任何替代存储，避免出现"IDEA 已登录、CodeBuddy 采集仍是旧身份"。
 */
public final class SharedAuthStore {

    private final Path authFile;

    public SharedAuthStore(@NotNull Path authFile) {
        this.authFile = authFile;
    }

    public boolean exists() {
        return Files.isRegularFile(authFile);
    }

    /** 读取会话；文件不存在返回 empty，密文损坏或结构非法抛出异常（不静默覆盖）。 */
    public @NotNull Optional<AuthSession> load() {
        if (!exists()) {
            return Optional.empty();
        }
        byte[] cipher;
        try {
            cipher = Files.readAllBytes(authFile);
        } catch (IOException e) {
            throw new CourseMindException("无法读取共享登录状态文件：" + authFile, e);
        }
        byte[] plain = Dpapi.unprotect(cipher);
        String json = new String(plain, StandardCharsets.UTF_8);
        try {
            Optional<AuthSession> result = AuthSession.fromJson(JsonParser.parseString(json));
            if (result.isEmpty()) throw new CourseMindException("共享登录状态结构无效，请重新登录");
            return result;
        } catch (RuntimeException e) {
            throw new CourseMindException("共享登录状态已损坏，请重新登录", e);
        }
    }

    public byte @NotNull [] readRaw() {
        try {
            return Files.readAllBytes(authFile);
        } catch (IOException e) {
            throw new CourseMindException("无法备份共享登录状态", e);
        }
    }

    public void save(@NotNull AuthSession session) {
        byte[] plain = session.toJson().toString().getBytes(StandardCharsets.UTF_8);
        write(Dpapi.protect(plain));
    }

    public void restoreRaw(byte @NotNull [] raw) {
        write(raw);
    }

    public void clear() {
        try {
            Files.deleteIfExists(authFile);
        } catch (IOException e) {
            throw new CourseMindException("无法删除共享登录状态文件，请检查文件占用情况", e);
        }
    }

    private void write(byte @NotNull [] cipher) {
        try {
            AtomicFiles.write(authFile, cipher);
        } catch (IOException e) {
            throw new CourseMindException("无法写入共享登录状态文件，请检查目录权限", e);
        }
    }
}
