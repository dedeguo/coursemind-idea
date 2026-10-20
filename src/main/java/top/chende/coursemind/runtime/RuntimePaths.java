package top.chende.coursemind.runtime;

import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 共享运行目录约定：%LOCALAPPDATA%\CourseMind（与 VS Code 扩展、collector 使用同一目录）。
 *
 * <p>IDEA 插件是第二个调用方，不得另建目录或另建凭据文件。
 */
public final class RuntimePaths {

    private final Path root;

    private RuntimePaths(@NotNull Path root) {
        this.root = root;
    }

    /** 指定根目录，供测试与自定义部署使用。 */
    public static @NotNull RuntimePaths of(@NotNull Path root) {
        return new RuntimePaths(root);
    }

    public static @NotNull RuntimePaths getDefault() {
        String localAppData = System.getenv("LOCALAPPDATA");
        Path base;
        if (localAppData != null && !localAppData.isBlank()) {
            base = Paths.get(localAppData);
        } else {
            base = Paths.get(System.getProperty("user.home", "."), "AppData", "Local");
        }
        return new RuntimePaths(base.resolve("CourseMind"));
    }

    public @NotNull Path root() {
        return root;
    }

    public @NotNull Path configFile() {
        return root.resolve("config.json");
    }

    public @NotNull Path identityFile() {
        return root.resolve("identity.json");
    }

    public @NotNull Path authFile() {
        return root.resolve("auth.dat");
    }

    public @NotNull Path cacheDir() {
        return root.resolve("cache");
    }

    public @NotNull Path collectorExe() {
        return root.resolve("collector.exe");
    }

    public @NotNull Path versionFile() {
        return root.resolve("version.json");
    }
}
