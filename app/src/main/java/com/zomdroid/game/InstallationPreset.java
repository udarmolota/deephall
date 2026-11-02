package com.zomdroid.game;

import androidx.annotation.NonNull;

public class InstallationPreset {
    public final String name;
    public final String buildVersion;
    public final String[] classPathArray;
    public final String[] extraJars;
    public final String[] libraryPathArray;
    public final String[] libraryPathForEmulationArray;
    public final String[] extraJvmArgs;
    public final String[] args;
    public final String mainClassName;
    /** A file inside the game directory whose presence means the game is installed. */
    public final String gameFileMarker;

    private InstallationPreset(Builder builder) {
        this.name = builder.name;
        this.buildVersion = builder.buildVersion;
        this.classPathArray = builder.classPathArray;
        this.extraJars = builder.extraJars;
        this.libraryPathArray = builder.libraryPathArray;
        this.libraryPathForEmulationArray = builder.libraryPathForEmulationArray;
        this.extraJvmArgs = builder.extraJvmArgs;
        this.args = builder.args;
        this.mainClassName = builder.mainClassName;
        this.gameFileMarker = builder.gameFileMarker;
    }

    @NonNull
    @Override
    public String toString() {
        return name;
    }

    public static class Builder {
        private String name = "";
        private String buildVersion = "";
        private String[] classPathArray = new String[0];
        private String[] extraJars = new String[0];
        private String[] libraryPathArray = new String[0];
        private String[] libraryPathForEmulationArray = new String[0];
        private String[] extraJvmArgs = new String[0];
        private String[] args = new String[0];
        private String mainClassName = "";
        private String gameFileMarker = "";

        public Builder setName(String name) {
            this.name = name;
            return this;
        }

        public Builder setBuildVersion(String buildVersion) {
          this.buildVersion = buildVersion;
          return this;
        }

        public Builder setClassPathArray(String[] classPathArray) {
            this.classPathArray = classPathArray;
            return this;
        }

        public Builder setExtraJars(String[] extraJars) {
            this.extraJars = extraJars;
            return this;
        }

        public Builder setLibraryPathArray(String[] libraryPathArray) {
            this.libraryPathArray = libraryPathArray;
            return this;
        }

        public Builder setLibraryPathForEmulationArray(String[] libraryPathForEmulationArray) {
            this.libraryPathForEmulationArray = libraryPathForEmulationArray;
            return this;
        }

        public Builder setExtraJvmArgs(String[] extraJvmArgs) {
            this.extraJvmArgs = extraJvmArgs;
            return this;
        }

        public Builder setArgs(String[] args) {
            this.args = args;
            return this;
        }

        public Builder setMainClassName(String mainClassName) {
            this.mainClassName = mainClassName;
            return this;
        }

        public Builder setGameFileMarker(String gameFileMarker) {
            this.gameFileMarker = gameFileMarker;
            return this;
        }

        public InstallationPreset build() {
            return new InstallationPreset(this);
        }
    }
}
