package top.chende.coursemind;

/**
 * 面向用户的可读异常：message 会直接展示在 IDEA 界面中，不包含堆栈或敏感内容。
 */
public class CourseMindException extends RuntimeException {

    public CourseMindException(String message) {
        super(message);
    }

    public CourseMindException(String message, Throwable cause) {
        super(message, cause);
    }
}
