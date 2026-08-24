set( LIB_SOURCES
    video.cpp
    tms9128nl.cpp
    SDL_FontCache.c
    led.cpp
    palette.cpp
    splash.cpp
    rgb2yuv.cpp
)

# rgb2yuv-gas.s is 32-bit-only (pushl/popl) and is only referenced under
# USE_MMX. Matching "x86" also matched x86_64, which fails to assemble.
if(CMAKE_SIZEOF_VOID_P EQUAL 4 AND CMAKE_SYSTEM_PROCESSOR MATCHES "^(i.86|x86|X86)$")
    list(APPEND LIB_SOURCES rgb2yuv-gas.s)
endif()

set( LIB_HEADERS
    led.h
    palette.h
    rgb2yuv.h
    SDL_FontCache.h
    tms9128nl.h
    video.h
    splash.h
    icon.h
    yuv2rgb_lookup.h
)

set_source_files_properties(SDL_FontCache.c PROPERTIES COMPILE_FLAGS -Wno-maybe-uninitialized)
set_source_files_properties(video.cpp PROPERTIES COMPILE_FLAGS -Wno-unused-const-variable)

add_library( video ${LIB_SOURCES} ${LIB_HEADERS} )
target_link_libraries( video plog )
