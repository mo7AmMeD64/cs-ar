package com.animeday;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

public final class FakeContext extends ContextWrapper {
    private final PackageManager pm;

    public FakeContext(Context base, String sourceDir) {
        super(base);
        this.pm = new FakePackageManager(base == null ? null : base.getPackageManager(), sourceDir);
    }

    @Override
    public String getPackageName() {
        FakePackageManager.log("Context.getPackageName");
        return FakePackageManager.PKG;
    }

    @Override
    public String getOpPackageName() {
        FakePackageManager.log("Context.getOpPackageName");
        return FakePackageManager.PKG;
    }

    @Override
    public PackageManager getPackageManager() {
        FakePackageManager.log("Context.getPackageManager");
        return pm;
    }

    @Override
    public Object getSystemService(String name) {
        FakePackageManager.log("Context.getSystemService(" + name + ")");
        // buildSecure only asks for "connectivity"; null is the tested path.
        return null;
    }

    @Override
    public ApplicationInfo getApplicationInfo() {
        try {
            return pm.getApplicationInfo(FakePackageManager.PKG, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return super.getApplicationInfo();
        }
    }

    @Override
    public Context getApplicationContext() {
        return this;
    }
}
