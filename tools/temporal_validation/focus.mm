#import <AppKit/AppKit.h>
#include <stdint.h>
extern "C" void metalmod_validation_activate() {
    [NSApp setActivationPolicy:NSApplicationActivationPolicyRegular];
    [NSApp activateIgnoringOtherApps:YES];
    for(NSWindow* window in NSApp.windows) if(window.visible) {
        // A disposable test window must remain visible alongside the normal full-screen client.
        // This affects only this test process, never the user's window/Space preferences.
        if(!(window.styleMask&NSWindowStyleMaskFullScreen))
            window.collectionBehavior=NSWindowCollectionBehaviorCanJoinAllSpaces|NSWindowCollectionBehaviorFullScreenAuxiliary;
        [window makeKeyAndOrderFront:nil];[window orderFrontRegardless];
    }
}
extern "C" uint64_t metalmod_validation_visibility() {
    uint64_t bits=NSApp.active?1:0;
    for(NSWindow* window in NSApp.windows) {
        if(window.visible)bits|=2;
        if(window.occlusionState&NSWindowOcclusionStateVisible)bits|=4;
        if(window.screen)bits|=8;
    }
    return bits;
}
