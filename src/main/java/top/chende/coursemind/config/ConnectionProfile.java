package top.chende.coursemind.config;

import top.chende.coursemind.CourseMindException;

/** Two explicitly configured entry points for one backend; identity follows the public URL. */
public record ConnectionProfile(String publicUrl, String campusUrl, String network) {
    public static final String DEFAULT_PUBLIC = "http://8.140.216.36:29236";
    public static final String DEFAULT_CAMPUS = "http://10.50.79.225:8000";

    public ConnectionProfile {
        publicUrl = ServerUrl.normalize(publicUrl);
        campusUrl = ServerUrl.normalize(campusUrl);
        network = "campus".equals(network) ? "campus" : "public";
    }
    public String effectiveUrl() { return "campus".equals(network) ? campusUrl : publicUrl; }
    public String serverId() { return publicUrl; }
    public String networkLabel() { return "campus".equals(network) ? "校园内网" : "公网"; }
    public void validate() {
        ServerUrl.validate(publicUrl).ifPresent(error -> { throw new CourseMindException("公网：" + error); });
        if (!campusUrl.isEmpty() || "campus".equals(network)) {
            ServerUrl.validate(campusUrl).ifPresent(error -> { throw new CourseMindException("校园内网：" + error); });
        }
    }
}
