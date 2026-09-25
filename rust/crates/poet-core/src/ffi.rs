//! JNI 桥接层，仅在 Android 目标下编译。
//!
//! 对应 Kotlin 类 `com.otaku.poet.jni.NativePoet` 中的静态原生方法。
//! 复杂参数与结果一律以 JSON 字符串传递，保持接口简单稳定。

use jni::objects::{JClass, JString};
use jni::sys::{jlong, jstring};
use jni::JNIEnv;

use crate::generator::GenOptions;
use crate::pack::{list_packs, Pack};

const RUNTIME_EXCEPTION: &str = "java/lang/RuntimeException";
const ILLEGAL_ARGUMENT: &str = "java/lang/IllegalArgumentException";

fn throw_new(env: &mut JNIEnv, class: &str, msg: impl AsRef<str>) {
    if let Err(e) = env.throw_new(class, msg.as_ref()) {
        // 无法抛出异常时，至少不能让进程崩溃；输出到 logcat。
        eprintln!("电子诗人：抛出 JNI 异常失败：{e}");
    }
}

fn input_string(env: &mut JNIEnv, s: &JString) -> Option<String> {
    match env.get_string(s) {
        Ok(java_str) => Some(java_str.into()),
        Err(e) => {
            throw_new(env, ILLEGAL_ARGUMENT, format!("读取字符串参数失败：{e}"));
            None
        }
    }
}

fn output_string(env: &mut JNIEnv, s: &str) -> jstring {
    match env.new_string(s) {
        Ok(js) => js.into_raw(),
        Err(e) => {
            eprintln!("电子诗人：创建返回字符串失败：{e}");
            std::ptr::null_mut()
        }
    }
}

/// 引擎版本。
#[no_mangle]
pub extern "system" fn Java_com_otaku_poet_jni_NativePoet_nativeVersion(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    output_string(&mut env, crate::core_version())
}

/// 扫描挂载根目录，返回 `PackSummary` 的 JSON 数组。
#[no_mangle]
pub extern "system" fn Java_com_otaku_poet_jni_NativePoet_nativeListPacks(
    mut env: JNIEnv,
    _class: JClass,
    root: JString,
) -> jstring {
    let Some(root) = input_string(&mut env, &root) else {
        return output_string(&mut env, "[]");
    };
    let summaries = list_packs(root);
    match serde_json::to_string(&summaries) {
        Ok(json) => output_string(&mut env, &json),
        Err(e) => {
            throw_new(&mut env, RUNTIME_EXCEPTION, format!("序列化词库列表失败：{e}"));
            std::ptr::null_mut()
        }
    }
}

/// 加载一个词库包，返回不透明句柄；失败返回 0 并抛出异常。
#[no_mangle]
pub extern "system" fn Java_com_otaku_poet_jni_NativePoet_nativeOpen(
    mut env: JNIEnv,
    _class: JClass,
    pack_dir: JString,
) -> jlong {
    let Some(dir) = input_string(&mut env, &pack_dir) else {
        return 0;
    };
    match Pack::load(dir) {
        Ok(pack) => Box::into_raw(Box::new(pack)) as jlong,
        Err(e) => {
            throw_new(&mut env, RUNTIME_EXCEPTION, e.to_string());
            0
        }
    }
}

/// 依据 JSON 参数生成诗歌，返回 `PoemOut` 的 JSON。
#[no_mangle]
pub extern "system" fn Java_com_otaku_poet_jni_NativePoet_nativeGenerate(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    opts_json: JString,
) -> jstring {
    if handle == 0 {
        throw_new(&mut env, RUNTIME_EXCEPTION, "词库引擎尚未打开（句柄为空）");
        return std::ptr::null_mut();
    }
    let pack = unsafe { &*(handle as *const Pack) };

    let Some(opts_str) = input_string(&mut env, &opts_json) else {
        return std::ptr::null_mut();
    };
    let options: GenOptions = match serde_json::from_str(&opts_str) {
        Ok(o) => o,
        Err(e) => {
            throw_new(
                &mut env,
                ILLEGAL_ARGUMENT,
                format!("生成参数解析失败：{e}"),
            );
            return std::ptr::null_mut();
        }
    };

    match pack.generate(&options) {
        Ok(poem) => match serde_json::to_string(&poem) {
            Ok(json) => output_string(&mut env, &json),
            Err(e) => {
                throw_new(&mut env, RUNTIME_EXCEPTION, format!("序列化诗歌失败：{e}"));
                std::ptr::null_mut()
            }
        },
        Err(e) => {
            throw_new(&mut env, RUNTIME_EXCEPTION, e.to_string());
            std::ptr::null_mut()
        }
    }
}

/// 关闭并释放词库包句柄。
#[no_mangle]
pub extern "system" fn Java_com_otaku_poet_jni_NativePoet_nativeClose(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle != 0 {
        unsafe {
            drop(Box::from_raw(handle as *mut Pack));
        }
    }
}
