import {
  withMainApplication,
  withAppDelegate,
  IOSConfig,
} from '@expo/config-plugins';

const withAndroidAction: any = (config: any) => {
  return withMainApplication(config, (config) => {
    if (!config.modResults.contents.includes('override fun getJSBundleFile(): String = OtaHotUpdate.bundleJS(this@MainApplication)')) {
      config.modResults.contents = config.modResults.contents.replace(
        /override val isHermesEnabled: Boolean = BuildConfig.IS_HERMES_ENABLED/g,
        `
          override val isHermesEnabled: Boolean = BuildConfig.IS_HERMES_ENABLED

          override fun getJSBundleFile(): String = OtaHotUpdate.bundleJS(this@MainApplication)`
      );
    }

    if (!config.modResults.contents.includes('import com.otahotupdate.OtaHotUpdate')) {
      config.modResults.contents = config.modResults.contents.replace(
        /import expo.modules.ReactNativeHostWrapper/g,
        `
import expo.modules.ReactNativeHostWrapper
import com.otahotupdate.OtaHotUpdate`
      );
    }
    return config;
  });
};

const withIosAction: any = (config: any) => {
  return withAppDelegate(config, (config) => {
    const appDelegatePath = config.modRequest.projectRoot + '/ios/' + IOSConfig.Paths.getAppDelegateFilePath(config.modRequest.projectRoot);
    const isSwift = appDelegatePath.endsWith('.swift');

    if (isSwift) {
      // Swift: AppDelegate.swift
      if (!config.modResults.contents.includes('import react_native_ota_hot_update')) {
        config.modResults.contents = `import react_native_ota_hot_update\n${config.modResults.contents}`;
      }

      if (!config.modResults.contents.includes('OtaHotUpdate.getBundle()')) {
        config.modResults.contents = config.modResults.contents.replace(
          /return Bundle.main.url\(forResource: "main", withExtension: "jsbundle"\)/,
          `return OtaHotUpdate.getBundle()`
        );
      }
    } else {
      // Objective-C: AppDelegate.mm
      if (!config.modResults.contents.includes('#import "OtaHotUpdate.h')) {
        config.modResults.contents = config.modResults.contents.replace(
          /#import "AppDelegate.h"/g,
          `#import "AppDelegate.h"
#import "OtaHotUpdate.h"`
        );
      }

      config.modResults.contents = config.modResults.contents.replace(
        /\[\[NSBundle mainBundle\] URLForResource:@\"main\" withExtension:@\"jsbundle\"\]/,
        `[OtaHotUpdate getBundle]`
      );
    }

    return config;
  });
};

const withAction: any = (config: any) => {
  config = withAndroidAction(config);
  config = withIosAction(config);
  return config;
};

module.exports = withAction;
