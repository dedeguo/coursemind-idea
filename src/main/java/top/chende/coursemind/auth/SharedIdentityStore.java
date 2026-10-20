package top.chende.coursemind.auth;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import top.chende.coursemind.CourseMindException;
import top.chende.coursemind.runtime.AtomicFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * 非敏感身份文件（%LOCALAPPDATA%\CourseMind\identity.json），供 collector 读取 student_id/project_id。
 *
 * <p>字段名与 VS Code 扩展写入的格式保持一致（snake_case）。
 */
public final class SharedIdentityStore {

    private final Path identityFile;

    public SharedIdentityStore(@NotNull Path identityFile) {
        this.identityFile = identityFile;
    }

    public void write(@NotNull AuthSession.Profile profile) {
        JsonObject json = new JsonObject();
        json.addProperty("student_id", profile.studentId());
        json.addProperty("student_no", profile.studentNo());
        json.addProperty("name", profile.name());
        if (profile.gender() != null) {
            json.addProperty("gender", profile.gender());
        }
        json.addProperty("class_name", profile.className());
        if (profile.projectId() != null) {
            json.addProperty("project_id", profile.projectId());
        }
        json.addProperty("updated_at", Instant.now().toString());
        String content = new GsonBuilder().setPrettyPrinting().create().toJson(json) + "\n";
        try {
            AtomicFiles.write(identityFile, content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new CourseMindException("无法写入 identity.json，请检查目录权限", e);
        }
    }

    public void clear() {
        try {
            Files.deleteIfExists(identityFile);
        } catch (IOException e) {
            throw new CourseMindException("无法删除 identity.json，请检查文件占用情况", e);
        }
    }
}
