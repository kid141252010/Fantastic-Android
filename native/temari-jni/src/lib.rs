use jni::objects::{JByteArray, JClass};
use jni::sys::{jbyteArray, jlong};
use jni::JNIEnv;
use std::ffi::c_void;
use std::panic::{catch_unwind, AssertUnwindSafe};

fn empty_array(env: &mut JNIEnv<'_>) -> jbyteArray {
    let _ = env.exception_clear();
    env.new_byte_array(0)
        .map(|array| array.into_raw())
        .unwrap_or_else(|_| {
            let _ = env.exception_clear();
            std::ptr::null_mut()
        })
}

#[no_mangle]
pub extern "system" fn Java_io_github_kid141252010_fantastic_playback_Temari_open(
    env: JNIEnv,
    _class: JClass,
    json: JByteArray,
) -> jlong {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if json.is_null() {
            return 0;
        }
        let bytes = match env.convert_byte_array(&json) {
            Ok(bytes) => bytes,
            Err(_) => return 0,
        };
        let handle = unsafe {
            temari::ffi::tmpl_from_json(bytes.as_ptr().cast(), bytes.len())
        };
        handle as isize as jlong
    }))
    .unwrap_or(0);
    if result == 0 {
        let _ = env.exception_clear();
    }
    result
}

#[no_mangle]
pub extern "system" fn Java_io_github_kid141252010_fantastic_playback_Temari_decrypt(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    sample: JByteArray,
) -> jbyteArray {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if sample.is_null() {
            return None;
        }
        let input = env.convert_byte_array(&sample).ok()?;
        let ptr = handle as isize as usize as *mut c_void;
        if ptr.is_null() {
            return None;
        }

        let mut output = vec![0u8; input.len()];
        let written = unsafe {
            temari::ffi::decrypt_sample_ffi(
                ptr,
                input.as_ptr(),
                input.len(),
                output.as_mut_ptr(),
            )
        };
        if written != input.len() {
            return None;
        }
        env.byte_array_from_slice(&output).ok()
    }));

    match result {
        Ok(Some(array)) => array.into_raw(),
        _ => empty_array(&mut env),
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_kid141252010_fantastic_playback_Temari_close(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        let ptr = handle as isize as usize as *mut c_void;
        if !ptr.is_null() {
            unsafe { temari::ffi::tmpl_destroy(ptr) };
        }
    }));
}
