#include "manymouse.h"

/* Android phase-1: disable ManyMouse platform backends and rely on SDL input paths. */
const ManyMouseDriver *ManyMouseDriver_windows = 0;
const ManyMouseDriver *ManyMouseDriver_evdev = 0;
const ManyMouseDriver *ManyMouseDriver_xinput2 = 0;
const ManyMouseDriver *ManyMouseDriver_hidmanager = 0;
const ManyMouseDriver *ManyMouseDriver_hidutilities = 0;
