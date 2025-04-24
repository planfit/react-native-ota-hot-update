#import "OtaHotUpdate.h"
#import <SSZipArchive/SSZipArchive.h>
static NSUncaughtExceptionHandler *previousHandler = NULL;
static BOOL isBeginning = NO;
@implementation OtaHotUpdate
RCT_EXPORT_MODULE()


- (instancetype)init {
    self = [super init];
    if (self) {
        previousHandler = NSGetUncaughtExceptionHandler();
        NSSetUncaughtExceptionHandler(&OTAExceptionHandler);
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(2.0 * NSEC_PER_SEC)), dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
            isBeginning = NO;
        });
    }
    return self;
}

void OTAExceptionHandler(NSException *exception) {
    NSUserDefaults *defaults = [NSUserDefaults standardUserDefaults];
    if (isBeginning) {
      NSString *oldPath = [defaults stringForKey:@"OLD_PATH"];
        if (oldPath) {
          BOOL isDeleted = [OtaHotUpdate removeBundleIfNeeded:@"PATH"];
          if (isDeleted) {
            [defaults setObject:oldPath forKey:@"PATH"];
            [defaults removeObjectForKey:@"OLD_PATH"];
          } else {
            [defaults removeObjectForKey:@"OLD_PATH"];
            [defaults removeObjectForKey:@"PATH"];
          }
        } else {
          [defaults removeObjectForKey:@"PATH"];
        }
      [defaults synchronize];
    } else if (previousHandler) {
        previousHandler(exception);
    }
}

// Check if a file path is valid
+ (BOOL)isFilePathValid:(NSString *)path {
    NSFileManager *fileManager = [NSFileManager defaultManager];
    return [fileManager fileExistsAtPath:path];
}

// Delete a file at the specified path
- (BOOL)deleteFileAtPath:(NSString *)path {
    NSFileManager *fileManager = [NSFileManager defaultManager];
    NSError *error = nil;
    BOOL success = [fileManager removeItemAtPath:path error:&error];
    if (!success) {
      NSLog(@"Error deleting file: %@", [error localizedDescription]);
    }
    return success;
}

+ (BOOL)removeBundleIfNeeded:(NSString *)pathKey {
    NSString *keyToUse = pathKey ? pathKey : @"OLD_PATH";
    NSUserDefaults *defaults = [NSUserDefaults standardUserDefaults];
    NSString *retrievedString = [defaults stringForKey:keyToUse];
    NSError *error = nil;
  if (retrievedString && [self isFilePathValid:retrievedString]) {
        BOOL isDeleted = [self deleteFileAtPath:retrievedString];
        [defaults removeObjectForKey:keyToUse];
        [defaults synchronize];
        return isDeleted;
    } else {
        return NO;
    }
}

+ (BOOL)isFilePathExist:(NSString *)path {
    NSFileManager *fileManager = [NSFileManager defaultManager];
    return [fileManager fileExistsAtPath:path];
}

+ (NSURL *)getBundle {
    NSUserDefaults *defaults = [NSUserDefaults standardUserDefaults];
    NSString *retrievedString = [defaults stringForKey:@"PATH"];
    NSString *currentVersionName = [defaults stringForKey:@"VERSION_NAME"];
    NSString *versionName = [[[NSBundle mainBundle] infoDictionary] objectForKey:@"CFBundleShortVersionString"];

  if (retrievedString && [self isFilePathExist:retrievedString] && [currentVersionName isEqualToString:versionName]) {
       NSURL *fileURL = [NSURL fileURLWithPath:retrievedString];
       return fileURL;
    } else {
         // reset version number because bundle is wrong version, need download from new version
        [defaults removeObjectForKey:@"VERSION"];
        [defaults synchronize];
        return [[NSBundle mainBundle] URLForResource:@"main" withExtension:@"jsbundle"];
    }
}

- (NSString *)searchForJsBundleInDirectory:(NSString *)directoryPath extension:(NSString *)extension {
    NSFileManager *fileManager = [NSFileManager defaultManager];
    NSError *error;

    // Get contents of the directory
    NSArray *contents = [fileManager contentsOfDirectoryAtPath:directoryPath error:&error];
    if (error) {
        NSLog(@"Error reading directory contents: %@", error.localizedDescription);
        return nil;
    }

    for (NSString *file in contents) {
        NSString *filePath = [directoryPath stringByAppendingPathComponent:file];
        BOOL isDirectory;
        if ([fileManager fileExistsAtPath:filePath isDirectory:&isDirectory]) {
            if (isDirectory) {
                // Recursively search in subdirectories
                NSString *foundPath = [self searchForJsBundleInDirectory:filePath extension:extension];
                if (foundPath) {
                    return foundPath;
                }
            } else if ([filePath hasSuffix:extension]) {
                // Return the path if it's a .jsbundle file
                return filePath;
            }
        }
    }

    return nil;
}
- (NSString *)renameExtractedFolderInDirectory:(NSString *)directoryPath {
    NSFileManager *fileManager = [NSFileManager defaultManager];
    NSError *error = nil;

    // Get the contents of the extracted directory
    NSArray *contents = [fileManager contentsOfDirectoryAtPath:directoryPath error:&error];
    if (error || contents.count != 1) {
        NSLog(@"Error retrieving extracted folder or unexpected structure: %@", error.localizedDescription);
        return nil;
    }

    // Get the original extracted folder name (assuming only one folder exists)
    NSString *originalFolderName = contents.firstObject;
    NSString *originalFolderPath = [directoryPath stringByAppendingPathComponent:originalFolderName];

    // Generate new folder name with timestamp
    NSString *timestamp = [NSString stringWithFormat:@"output_%ld", (long)[[NSDate date] timeIntervalSince1970]];
    NSString *newFolderPath = [directoryPath stringByAppendingPathComponent:timestamp];

    // Rename the extracted folder
    if (![fileManager moveItemAtPath:originalFolderPath toPath:newFolderPath error:&error]) {
        NSLog(@"Failed to rename folder: %@", error.localizedDescription);
        return nil;
    }

    NSLog(@"Renamed extracted folder to: %@", newFolderPath);
    return newFolderPath;
}
- (NSString *)unzipFileAtPath:(NSString *)zipFilePath extension:(NSString *)extension  {
    // Define the directory where the files will be extracted
    NSString *extractedFolderPath = [[zipFilePath stringByDeletingPathExtension] stringByAppendingPathExtension:@"unzip"];

    // Create the directory if it does not exist
    NSFileManager *fileManager = [NSFileManager defaultManager];
    if (![fileManager fileExistsAtPath:extractedFolderPath]) {
        NSError *error = nil;
        [fileManager createDirectoryAtPath:extractedFolderPath withIntermediateDirectories:YES attributes:nil error:&error];
        if (error) {
            [self deleteFileAtPath:zipFilePath];
            NSLog(@"Failed to create directory: %@", error.localizedDescription);
            return nil;
        }
    }

    // Unzip the file
    BOOL success = [SSZipArchive unzipFileAtPath:zipFilePath toDestination:extractedFolderPath];
    if (!success) {
        [self deleteFileAtPath:zipFilePath];
        NSLog(@"Failed to unzip file");
        return nil;
    }
  // Try renaming the extracted folder
      NSString *renamedFolderPath = [self renameExtractedFolderInDirectory:extractedFolderPath];

      // If renaming fails, use the original extracted folder path
      NSString *finalFolderPath = renamedFolderPath ? renamedFolderPath : extractedFolderPath;

    // Find .jsbundle files in the extracted directory
    NSString *jsbundleFilePath = [self searchForJsBundleInDirectory:finalFolderPath extension:extension];

        // Delete the zip file after extraction
        NSError *removeError = nil;
        [fileManager removeItemAtPath:zipFilePath error:&removeError];
        if (removeError) {
            NSLog(@"Failed to delete zip file: %@", removeError.localizedDescription);
        }
        return jsbundleFilePath;
}

// Expose setupBundlePath method to JavaScript
RCT_EXPORT_METHOD(setupBundlePath:(NSString *)path extension:(NSString *)extension
                  resolve:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject) {
    if ([OtaHotUpdate isFilePathValid:path]) {
        [OtaHotUpdate removeBundleIfNeeded:nil];
        //Unzip file
        NSString *extractedFilePath = [self unzipFileAtPath:path extension:(extension != nil) ? extension : @".jsbundle"];
        if (extractedFilePath) {
            NSUserDefaults *defaults = [NSUserDefaults standardUserDefaults];
            NSString *oldPath = [defaults stringForKey:@"PATH"];
            if (oldPath) {
              [defaults setObject:oldPath forKey:@"OLD_PATH"];
            }
            [defaults setObject:extractedFilePath forKey:@"PATH"];
            [defaults setObject:[[[NSBundle mainBundle] infoDictionary] objectForKey:@"CFBundleShortVersionString"] forKey:@"VERSION_NAME"];
            [defaults synchronize];
            isBeginning = YES;
            resolve(@(YES));
        } else {
            resolve(@(NO));
        }
    } else {
        resolve(@(NO));
    }
}
RCT_EXPORT_METHOD(deleteBundle:(double)i
                  resolve:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject) {
    BOOL isDeleted = [OtaHotUpdate removeBundleIfNeeded:@"PATH"];
    BOOL isDeletedOld = [OtaHotUpdate removeBundleIfNeeded:nil];
    if (isDeleted && isDeletedOld) {
          resolve(@(YES));
      } else {
          resolve(@(NO));
      }
}
// Expose deleteBundle method to JavaScript
RCT_EXPORT_METHOD(rollbackToPreviousBundle:(double)i
                  resolve:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject) {
  NSUserDefaults *defaults = [NSUserDefaults standardUserDefaults];
  NSString *oldPath = [defaults stringForKey:@"OLD_PATH"];
    if (oldPath && [OtaHotUpdate isFilePathValid:oldPath]) {
      BOOL isDeleted = [OtaHotUpdate removeBundleIfNeeded:@"PATH"];
      if (isDeleted) {
        NSString *previousVersion = [defaults stringForKey:@"PREVIOUS_VERSION"];
        if (previousVersion) {
          [defaults setObject:previousVersion forKey:@"VERSION"];
          [defaults removeObjectForKey:@"PREVIOUS_VERSION"];
        } else {
          [defaults removeObjectForKey:@"VERSION"];
        }

        [defaults setObject:oldPath forKey:@"PATH"];
        [defaults removeObjectForKey:@"OLD_PATH"];
        [defaults synchronize];
        resolve(@(YES));
      } else {
          resolve(@(NO));
        }
      } else {
          resolve(@(NO));
      }
}

RCT_EXPORT_METHOD(getCurrentVersion:(double)a
                  resolve:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject)
{
  NSUserDefaults *defaults = [NSUserDefaults standardUserDefaults];
  NSString *version = [defaults stringForKey:@"VERSION"];
     if (version) {
         resolve(version);
     } else {
         resolve(@"0");
     }
}

RCT_EXPORT_METHOD(setCurrentVersion:(NSString *)version
                  resolve:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject) {
    NSUserDefaults *defaults = [NSUserDefaults standardUserDefaults];
    NSString *currentVersion = [defaults stringForKey:@"VERSION"];

    if (version) {
        if (currentVersion && currentVersion != version) {
            [defaults setObject:currentVersion forKey:@"PREVIOUS_VERSION"];
        }

        [defaults setObject:version forKey:@"VERSION"];
        [defaults synchronize];
        resolve(@(YES));
    } else {
        resolve(@(NO));
    }
}

RCT_EXPORT_METHOD(setUpdateMetadata:(NSString *)metadataString
                  resolve:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject) {
    if (metadataString) {
        NSUserDefaults *defaults = [NSUserDefaults standardUserDefaults];
        [defaults setObject:metadataString forKey:@"METADATA"];
        [defaults synchronize];
        resolve(@(YES));
    } else {
        resolve(@(NO));
    }
}

RCT_EXPORT_METHOD(getUpdateMetadata:(double)a
                  resolve:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject)
{
    NSUserDefaults *defaults = [NSUserDefaults standardUserDefaults];
    NSString *metadata = [defaults stringForKey:@"METADATA"];

    if (metadata) {
        resolve(metadata);
    } else {
        resolve(nil);
    }
}

RCT_EXPORT_METHOD(setExactBundlePath:(NSString *)path
                  resolve:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject) {
    if (path) {
        NSUserDefaults *defaults = [NSUserDefaults standardUserDefaults];
        [defaults setObject:path forKey:@"PATH"];
        [defaults setObject:[[[NSBundle mainBundle] infoDictionary] objectForKey:@"CFBundleShortVersionString"] forKey:@"VERSION_NAME"];
        [defaults synchronize];
        resolve(@(YES));
    } else {
        resolve(@(NO));
    }
}

- (void)loadBundle
{
    RCTTriggerReloadCommandListeners(@"react-native-ota-hot-update: Restart");
}
RCT_EXPORT_METHOD(restart) {
    if ([NSThread isMainThread]) {
        [self loadBundle];
    } else {
        dispatch_sync(dispatch_get_main_queue(), ^{
            [self loadBundle];
        });
    }
    return;
}


// Don't compile this code when we build for the old architecture.
#ifdef RCT_NEW_ARCH_ENABLED
- (std::shared_ptr<facebook::react::TurboModule>)getTurboModule:
    (const facebook::react::ObjCTurboModule::InitParams &)params
{
    return std::make_shared<facebook::react::NativeOtaHotUpdateSpecJSI>(params);
}
#endif

@end
