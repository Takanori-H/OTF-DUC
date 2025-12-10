package ltsa.ui;

public class EnvConfiguration {
    private String openFileName = null;

    private static EnvConfiguration instance = null;

    private EnvConfiguration() {
    }

    public static EnvConfiguration getInstance() {
        if (instance == null) {
            instance = new EnvConfiguration();
        }
        return instance;
    }

    public String getOpenFileName() {
        return openFileName;
    }

    public void setOpenFileName(String openFileName) {
        this.openFileName = openFileName;
    }
}
