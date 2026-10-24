package top.chende.coursemind.education;
import top.chende.coursemind.config.ServerUrl;

/** Ordinary browser login flow; no token, ticket or credential is put in a URL. */
public final class WorkbenchLinks {
    private WorkbenchLinks() { }
    public static String tasks(String entry) { return ServerUrl.normalize(entry) + "/education/tasks"; }
    public static String task(String entry, int id) { if (id <= 0) throw new IllegalArgumentException(); return tasks(entry) + "/" + id; }
    public static String sessions(String entry) { return ServerUrl.normalize(entry) + "/education/sessions"; }
    public static String session(String entry, long id) { if (id <= 0) throw new IllegalArgumentException(); return sessions(entry) + "/" + id; }
}
