package top.chende.coursemind.config;
import org.junit.Test;
import static org.junit.Assert.*;

public class CourseMindSettingsTest {
    @Test public void freshDefaultsAndCampusIdentity() {
        CourseMindSettings s = new CourseMindSettings();
        assertEquals(ConnectionProfile.DEFAULT_PUBLIC, s.getServerUrl());
        s.setConnection(new ConnectionProfile(ConnectionProfile.DEFAULT_PUBLIC, ConnectionProfile.DEFAULT_CAMPUS, "campus"));
        assertEquals(ConnectionProfile.DEFAULT_CAMPUS, s.getServerUrl());
        assertEquals(ConnectionProfile.DEFAULT_PUBLIC, s.connection().serverId());
    }
    @Test public void legacyAddressIsRetainedWithoutGuessingNetwork() {
        CourseMindSettings.State old = new CourseMindSettings.State();
        old.serverUrl = "http://custom-school:8000/"; old.configVersion = 1;
        CourseMindSettings s = new CourseMindSettings(); s.loadState(old);
        assertEquals("http://custom-school:8000", s.getServerUrl());
        assertEquals("public", s.connection().network());
        assertFalse(s.migrationNotice().isBlank());
        s.adoptShared("http://other:1", "http://other:1");
        assertEquals("http://custom-school:8000", s.getServerUrl());
    }
    @Test public void savedSettingsSurviveReloadAndUpgrade() {
        CourseMindSettings s = new CourseMindSettings();
        ConnectionProfile p = new ConnectionProfile("https://custom", "http://school:9", "campus");
        s.setConnection(p);
        CourseMindSettings loaded = new CourseMindSettings(); loaded.loadState(s.getState());
        assertEquals(p, loaded.connection());
        loaded.adoptShared("http://other", "http://other"); assertEquals(p, loaded.connection());
    }
    @Test public void firstUseAdoptsSharedInstanceAndEntrySeparately() {
        CourseMindSettings s = new CourseMindSettings();
        s.adoptShared("http://school:8000", "https://public");
        assertEquals("https://public", s.connection().serverId());
        assertEquals("http://school:8000", s.getServerUrl());
        assertEquals("campus", s.connection().network());
    }
    @Test public void emptyCampusIsAllowedOnlyWhenNotSelected() {
        new ConnectionProfile("http://public", "", "public").validate();
        assertThrows(RuntimeException.class, () -> new ConnectionProfile("http://public", "", "campus").validate());
    }
    @Test public void invalidSharedConfigIsNotSilentlyReplaced() {
        CourseMindSettings s = new CourseMindSettings();
        assertThrows(RuntimeException.class, () -> s.adoptShared("ftp://school", "ftp://school"));
        assertFalse(s.getState().connectionSaved);
    }
}
