#import <AppKit/AppKit.h>
// Test-only application activation, excluded from both production and validation JARs.
extern "C" void metalmod_validation_activate() { [NSApp activateIgnoringOtherApps:YES]; }
