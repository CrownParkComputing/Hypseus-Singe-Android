############################################################
# BuildAndroidDeps.cmake
# Builds SDL2 family, libogg, libvorbis and libzip from source
# for Android using FetchContent (no autotools required).
############################################################

include(FetchContent)

# Prefer the Android app-local dependency cache first, then fall back to the
# historical root cache location.
set(HYPSEUS_ANDROID_DEPS_CACHE "")
set(_hypseus_android_deps_candidates
    "${CMAKE_SOURCE_DIR}/../../../../build-android-arm64/_deps"
    "${CMAKE_CURRENT_SOURCE_DIR}/../build-android-arm64/_deps"
)

foreach(_deps_candidate IN LISTS _hypseus_android_deps_candidates)
    if(EXISTS "${_deps_candidate}")
        set(HYPSEUS_ANDROID_DEPS_CACHE "${_deps_candidate}")
        break()
    endif()
endforeach()

if(HYPSEUS_ANDROID_DEPS_CACHE STREQUAL "")
    # Keep behavior deterministic even when cache folder is absent.
    set(HYPSEUS_ANDROID_DEPS_CACHE "${CMAKE_CURRENT_SOURCE_DIR}/../build-android-arm64/_deps")
endif()

function(hypseus_fetchcontent_declare name cache_dir archive_url)
    if(EXISTS "${HYPSEUS_ANDROID_DEPS_CACHE}/${cache_dir}/CMakeLists.txt")
        FetchContent_Declare(${name}
            SOURCE_DIR "${HYPSEUS_ANDROID_DEPS_CACHE}/${cache_dir}"
        )
    else()
        FetchContent_Declare(${name}
            URL "${archive_url}"
        )
    endif()
endfunction()

# Ensure SDL2_SHARED stays OFF globally so SDL2_ttf's INTERFACE_SDL2_SHARED
# property agrees with the SDL2-static target that hypseus links against.
set(SDL2_SHARED OFF CACHE BOOL "" FORCE)

# ---- SDL2 ----
hypseus_fetchcontent_declare(SDL2
    sdl2-src
    https://github.com/libsdl-org/SDL/archive/refs/tags/release-2.30.9.zip
)
set(SDL_SHARED OFF CACHE BOOL "" FORCE)
set(SDL_STATIC ON  CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(SDL2)

# ---- SDL2_image ----
hypseus_fetchcontent_declare(SDL2_image
    sdl2_image-src
    https://github.com/libsdl-org/SDL_image/archive/refs/tags/release-2.8.3.zip
)
set(SDL2IMAGE_SAMPLES OFF CACHE BOOL "" FORCE)
set(SDL2IMAGE_SDL2_SHARED OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(SDL2_image)
if(TARGET SDL2_image)
    set_property(TARGET SDL2_image PROPERTY INTERFACE_SDL2_SHARED 0)
endif()

# ---- SDL2_ttf ----
hypseus_fetchcontent_declare(SDL2_ttf
    sdl2_ttf-src
    https://github.com/libsdl-org/SDL_ttf/archive/refs/tags/release-2.22.0.zip
)
set(SDL2TTF_SAMPLES OFF CACHE BOOL "" FORCE)
set(SDL2TTF_SDL2_SHARED OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(SDL2_ttf)
if(TARGET SDL2_ttf)
    set_property(TARGET SDL2_ttf PROPERTY INTERFACE_SDL2_SHARED 0)
endif()

# Force ogg/vorbis static so they merge into libhypseus.so (avoids separate
# .so files that exhaust mmap regions and trigger RELRO "Out of memory").
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

# ---- libogg (must be before SDL2_mixer to win the target-name race) ----
hypseus_fetchcontent_declare(libogg
    libogg-src
    https://github.com/xiph/ogg/archive/refs/tags/v1.3.5.zip
)
FetchContent_MakeAvailable(libogg)

# ---- libvorbis ----
hypseus_fetchcontent_declare(libvorbis
    libvorbis-src
    https://github.com/xiph/vorbis/archive/refs/tags/v1.3.7.zip
)
FetchContent_MakeAvailable(libvorbis)

# ---- SDL2_mixer ----
# Use stb_vorbis (built-in) and disable Opus so SDL2_mixer does NOT vendor its
# own libogg, which would conflict with the ogg target created above.
hypseus_fetchcontent_declare(SDL2_mixer
    sdl2_mixer-src
    https://github.com/libsdl-org/SDL_mixer/archive/refs/tags/release-2.8.0.zip
)
set(SDL2MIXER_SAMPLES       OFF CACHE BOOL "" FORCE)
set(SDL2MIXER_VORBIS        "STB" CACHE STRING "" FORCE)   # stb_vorbis, no libogg needed
set(SDL2MIXER_VORBIS_STB    ON  CACHE BOOL "" FORCE)
set(SDL2MIXER_OPUS          OFF CACHE BOOL "" FORCE)       # Opus also vendors ogg
set(SDL2MIXER_OGG_VENDORED  OFF CACHE BOOL "" FORCE)       # don't vendor libogg
set(SDL2MIXER_WAVPACK       OFF CACHE BOOL "" FORCE)
set(SDL2MIXER_FLAC          OFF CACHE BOOL "" FORCE)
set(SDL2MIXER_MOD           OFF CACHE BOOL "" FORCE)
set(SDL2MIXER_SDL2_SHARED   OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(SDL2_mixer)
if(TARGET SDL2_mixer)
    set_property(TARGET SDL2_mixer PROPERTY INTERFACE_SDL2_SHARED 0)
endif()

# ---- libzip ----
set(BUILD_SHARED_LIBS      OFF CACHE BOOL "" FORCE)
set(ENABLE_OPENSSL         OFF CACHE BOOL "" FORCE)
set(ENABLE_MBEDTLS         OFF CACHE BOOL "" FORCE)
set(ENABLE_WINDOWS_CRYPTO  OFF CACHE BOOL "" FORCE)
set(BUILD_REGRESS          OFF CACHE BOOL "" FORCE)
set(BUILD_EXAMPLES         OFF CACHE BOOL "" FORCE)
set(BUILD_DOC              OFF CACHE BOOL "" FORCE)
set(BUILD_TOOLS            OFF CACHE BOOL "" FORCE)
hypseus_fetchcontent_declare(libzip
    libzip-src
    https://github.com/nih-at/libzip/archive/refs/tags/v1.10.1.zip
)
FetchContent_MakeAvailable(libzip)

# Expose variables matching desktop pkg-config names used by src/CMakeLists.txt.
set(SDL2_INCLUDE_DIRS      "${sdl2_SOURCE_DIR}/include")
set(SDL2_TTF_INCLUDE_DIRS  "${sdl2_ttf_SOURCE_DIR}")
set(SDL2_IMAGE_INCLUDE_DIRS "${sdl2_image_SOURCE_DIR}/include")
set(SDL2_MIXER_INCLUDE_DIRS "${sdl2_mixer_SOURCE_DIR}/include")
set(SDL2_LIBRARIES         SDL2-static)
set(SDL2_TTF_LIBRARY       SDL2_ttf)
set(SDL2_IMAGE_LIBRARY     SDL2_image)
set(SDL2_MIXER_LIBRARY     SDL2_mixer)
set(OGG_INCLUDE_DIRS       ${libogg_SOURCE_DIR}/include)
set(OGG_LIBRARIES          ogg)
set(VORBIS_INCLUDE_DIRS    ${libvorbis_SOURCE_DIR}/include)
set(Vorbis_File_LIBRARY    vorbisfile)
set(Vorbis_Enc_LIBRARY     vorbisenc)
set(VORBISFILE_LIBRARIES   vorbisfile)
set(LIBZIP_LIBRARY         zip)
