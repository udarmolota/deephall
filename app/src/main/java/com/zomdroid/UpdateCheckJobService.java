package com.zomdroid;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** The daily background run of {@link UpdateChecker}, scheduled by it on every app start. */
public class UpdateCheckJobService extends JobService {

    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            UpdateChecker.checkIfDue(getApplicationContext());
            jobFinished(params, false);
        }, "deephall-update-job").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false; // The next daily run tries again
    }
}
