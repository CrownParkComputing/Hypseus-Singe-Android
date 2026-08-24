set( LIB_SOURCES
    sound.cpp
    ssi263.cpp
    tqsynth.cpp
    sn_intf.cpp
    tms9919-sdl.cpp
    tms9919.cpp
    pc_beeper.cpp
    gisound.cpp
    dac.cpp
    tonegen.cpp
    samples.cpp
    mix.cpp
)

# mix_mmx-gas.s is 32-bit-only and only referenced under USE_MMX.
if(CMAKE_SIZEOF_VOID_P EQUAL 4 AND CMAKE_SYSTEM_PROCESSOR MATCHES "^(i.86|x86|X86)$")
    list(APPEND LIB_SOURCES mix_mmx-gas.s)
endif()

set( LIB_HEADERS
    dac.h
    gisound.h
    mix.h
    pc_beeper.h
    samples.h
    sn_intf.h
    sound.h
    ssi263.h
    tonegen.h
    tqsynth.h
)

add_library( sound ${LIB_SOURCES} ${LIB_HEADERS} )
target_link_libraries( sound plog )
