use jni::objects::{JByteArray, JClass};
use jni::sys::{jbyteArray, jlong};
use jni::JNIEnv;
use std::ffi::{c_char, c_void};

fn empty_array(env: &mut JNIEnv<'_>) -> jbyteArray {
    env.byte_array_from_slice(&[])
        .map(|array| array.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_io_github_kid141252010_fantastic_playback_Temari_open(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    json: JByteArray<'_>,
) -> jlong {
    let bytes = match env.convert_byte_array(json) {
        Ok(bytes) => bytes,
        Err(_) => return 0,
    };
    let handle = unsafe {
        temari::ffi::tmpl_from_json(bytes.as_ptr() as *const c_char, bytes.len())
    };
    handle as jlong
}

#[no_mangle]
pub extern "system" fn Java_io_github_kid141252010_fantastic_playback_Temari_decrypt(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    sample: JByteArray<'_>,
) -> jbyteArray {
    if handle == 0 {
        return empty_array(&mut env);
    }
    let input = match env.convert_byte_array(sample) {
        Ok(input) => input,
        Err(_) => return empty_array(&mut env),
    };
    let mut output = vec![0_u8; input.len()];
    let written = unsafe {
        temari::ffi::decrypt_sample_ffi(
            handle as *const c_void,
            input.as_ptr(),
            input.len(),
            output.as_mut_ptr(),
        )
    };
    if written != input.len() {
        return empty_array(&mut env);
    }
    env.byte_array_from_slice(&output)
        .map(|array| array.into_raw())
        .unwrap_or_else(|_| empty_array(&mut env))
}

#[no_mangle]
pub extern "system" fn Java_io_github_kid141252010_fantastic_playback_Temari_close(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) {
    if handle != 0 {
        unsafe { temari::ffi::tmpl_destroy(handle as *mut c_void) };
    }
}
