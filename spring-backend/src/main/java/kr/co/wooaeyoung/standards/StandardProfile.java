package kr.co.wooaeyoung.standards;

public enum StandardProfile {
    FEDIAF_2025("FEDIAF-2025.09", "fediaf-2025"),
    NIAS_V3("NIAS-v3", "nias-v3");

    public static final StandardProfile DEFAULT = FEDIAF_2025;

    private final String version;
    private final String directory;

    StandardProfile(String version, String directory) {
        this.version = version;
        this.directory = directory;
    }

    public String version() { return version; }
    /** classpath:standards/{directory}/ 아래에 프로필별 기준 데이터가 있다. */
    public String directory() { return directory; }
}
