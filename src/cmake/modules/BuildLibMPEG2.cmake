macro( build_libmpeg2 )

include( CheckCCompilerFlag )

check_c_compiler_flag(
    "-Wdeprecated-non-prototype"
    HAVE_DEPRECATED_NON_PROTOTYPE
)

set( LIBMPEG2_COMPILE_OPTIONS -std=gnu89 )

if( HAVE_DEPRECATED_NON_PROTOTYPE )
    list( APPEND LIBMPEG2_COMPILE_OPTIONS -Wno-deprecated-non-prototype )
endif()

string( JOIN " " LIBMPEG2_CFLAGS ${LIBMPEG2_COMPILE_OPTIONS} )

set( LIBMPEG2_ARCHIVE ${CMAKE_CURRENT_SOURCE_DIR}/3rdparty/libmpeg2/libmpeg2-master.tgz )
set( LIBMPEG2_ARCHIVE_SHA256 5aad06f396553c5b6afb5393ff26187bb1120928d6ed4f88d2482dd41d04cf75 )
set( LIBMPEG2_ARCHIVE_FALLBACK_URL
    https://github.com/DirtBagXon/hypseus-singe/raw/master/src/3rdparty/libmpeg2/libmpeg2-master.tgz
)

set( _libmpeg2_have_valid_archive FALSE )
if( EXISTS ${LIBMPEG2_ARCHIVE} )
    file( SHA256 ${LIBMPEG2_ARCHIVE} _libmpeg2_archive_sha256 )
    if( _libmpeg2_archive_sha256 STREQUAL LIBMPEG2_ARCHIVE_SHA256 )
        set( _libmpeg2_have_valid_archive TRUE )
    endif()
endif()

if( NOT _libmpeg2_have_valid_archive )
    file( DOWNLOAD
        ${LIBMPEG2_ARCHIVE_FALLBACK_URL}
        ${LIBMPEG2_ARCHIVE}
        EXPECTED_HASH SHA256=${LIBMPEG2_ARCHIVE_SHA256}
        SHOW_PROGRESS
    )
endif()

if( ANDROID )
    set( LIBMPEG2_ROOT ${CMAKE_CURRENT_BINARY_DIR}/3rdparty/libmpeg2-src )
    set( LIBMPEG2_SRC_ROOT ${LIBMPEG2_ROOT}/libmpeg2-master )
    set( LIBMPEG2_CONFIG_H ${LIBMPEG2_SRC_ROOT}/config.h )

    if( NOT EXISTS ${LIBMPEG2_SRC_ROOT}/libmpeg2/decode.c )
        file( REMOVE_RECURSE ${LIBMPEG2_ROOT} )
        file( MAKE_DIRECTORY ${LIBMPEG2_ROOT} )
        file( ARCHIVE_EXTRACT INPUT ${LIBMPEG2_ARCHIVE} DESTINATION ${LIBMPEG2_ROOT} )
    endif()

    file( WRITE ${LIBMPEG2_CONFIG_H}
"/* Auto-generated for Android CMake build */\n
#define HAVE_INTTYPES_H 1\n
#define HAVE_STDINT_H 1\n
#define HAVE_MEMORY_H 1\n
#define HAVE_STDLIB_H 1\n
#define HAVE_STRING_H 1\n
#define HAVE_STRINGS_H 1\n
#define HAVE_SYS_STAT_H 1\n
#define HAVE_SYS_TYPES_H 1\n
#define HAVE_UNISTD_H 1\n
#define STDC_HEADERS 1\n
#define PACKAGE \"mpeg2dec\"\n
#define VERSION \"0.5.1\"\n
#define RETSIGTYPE void\n
")

    add_library( libmpeg2 STATIC
        ${LIBMPEG2_SRC_ROOT}/libmpeg2/alloc.c
        ${LIBMPEG2_SRC_ROOT}/libmpeg2/header.c
        ${LIBMPEG2_SRC_ROOT}/libmpeg2/decode.c
        ${LIBMPEG2_SRC_ROOT}/libmpeg2/slice.c
        ${LIBMPEG2_SRC_ROOT}/libmpeg2/motion_comp.c
        ${LIBMPEG2_SRC_ROOT}/libmpeg2/idct.c
        ${LIBMPEG2_SRC_ROOT}/libmpeg2/cpu_accel.c
        ${LIBMPEG2_SRC_ROOT}/libmpeg2/cpu_state.c
    )

    target_include_directories( libmpeg2 PUBLIC
        ${LIBMPEG2_SRC_ROOT}
        ${LIBMPEG2_SRC_ROOT}/include
        ${LIBMPEG2_SRC_ROOT}/libmpeg2
    )

    target_compile_options( libmpeg2 PRIVATE ${LIBMPEG2_COMPILE_OPTIONS} )

    set( MPEG2_INCLUDE_DIRS ${LIBMPEG2_SRC_ROOT}/include )
    set( MPEG2_LIBRARIES libmpeg2 )
else()
    find_program( LIBTOOLIZE_EXECUTABLE
        NAMES glibtoolize libtoolize
        REQUIRED )

    if( CMAKE_CROSSCOMPILING )
        string( REGEX MATCH "([-A-Za-z0-9\\._]+)-(gcc|cc)$" RESULT ${CMAKE_C_COMPILER} )
        string( REGEX REPLACE "-(gcc|cc)$" "" RESULT ${RESULT} )
        set( CONFIGURE_ARGS "--host=${RESULT}" )
    endif()

    externalproject_add( libmpeg2
        PREFIX ${CMAKE_CURRENT_BINARY_DIR}/3rdparty
        URL ../../../src/3rdparty/libmpeg2/libmpeg2-master.tgz
        URL_HASH SHA256=${LIBMPEG2_ARCHIVE_SHA256}

        CONFIGURE_COMMAND
            ${LIBTOOLIZE_EXECUTABLE} --copy --force &&
            autoreconf -f -i &&
            <SOURCE_DIR>/configure
            ${CONFIGURE_ARGS}
            --quiet
            --prefix=${CMAKE_CURRENT_BINARY_DIR}/3rdparty
            --disable-shared
            --enable-static
            --disable-sdl

        BUILD_IN_SOURCE 1
        BUILD_COMMAND make V=0 CFLAGS=${LIBMPEG2_CFLAGS}
        INSTALL_DIR ${CMAKE_CURRENT_BINARY_DIR}/3rdparty
        INSTALL_COMMAND make LIBTOOLFLAGS=--silent install
        ${DOWNLOAD_ARGS}
    )

    set( MPEG2_INCLUDE_DIRS ${CMAKE_CURRENT_BINARY_DIR}/3rdparty/include/mpeg2dec )
    set( MPEG2_LIBRARIES ${CMAKE_CURRENT_BINARY_DIR}/3rdparty/lib/libmpeg2.a )
endif()

set( MPEG2_FOUND ON )

endmacro( build_libmpeg2 )
