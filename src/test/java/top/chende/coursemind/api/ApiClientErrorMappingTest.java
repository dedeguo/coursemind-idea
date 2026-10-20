package top.chende.coursemind.api;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ApiClientErrorMappingTest {

    @Test
    public void unauthorizedPrefersServerMessage() {
        assertEquals("学号或密码错误", ApiClient.mapError(401, "学号或密码错误", null));
    }

    @Test
    public void unauthorizedWithoutMessageFallsBackToChineseHint() {
        assertTrue(ApiClient.mapError(401, null, null).contains("重新登录"));
    }

    @Test
    public void validationErrorIncludesFieldDetail() {
        assertTrue(ApiClient.mapError(422, null, "field required").contains("field required"));
    }

    @Test
    public void notFoundSuggestsServerVersionMismatch() {
        assertTrue(ApiClient.mapError(404, null, null).contains("服务端版本"));
    }

    @Test
    public void serverErrorIsReportedWithStatus() {
        assertTrue(ApiClient.mapError(503, null, null).contains("503"));
    }

    @Test
    public void networkFailureHasNoStatus() {
        assertTrue(ApiClient.mapError(0, null, null).contains("无法连接服务器"));
    }

    @Test
    public void parsesValidationErrorsFromServerEnvelope() {
        String body = "{\"code\":422,\"message\":\"validation error\",\"data\":{\"errors\":["
                + "{\"type\":\"missing\",\"loc\":[\"body\",\"password\"],\"msg\":\"Field required\"}]}}";
        assertEquals("password（Field required）", ApiClient.validationDetail(body));
    }

    @Test
    public void parsesValidationDetailFromFastApiDefault() {
        String body = "{\"detail\":[{\"loc\":[\"body\",\"student_no\"],\"msg\":\"field required\"}]}";
        assertEquals("student_no（field required）", ApiClient.validationDetail(body));
    }

    @Test
    public void unknownValidationShapeReturnsNull() {
        assertEquals(null, ApiClient.validationDetail("{\"code\":422,\"message\":\"validation error\"}"));
    }
}
