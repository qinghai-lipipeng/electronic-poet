//! # poet-core —— 电子诗人核心引擎
//!
//! 一个纯粹的现代诗“模板匹配 + 嵌词”引擎：
//!
//! 1. 从**可挂载**的语言资源包（目录）加载语法模板与分类词库；
//! 2. 生成时随机抽取模板，再从词库随机取词填入占位符；
//! 3. 可选按韵部押韵。
//!
//! 本引擎不含任何模型、概率学习或神经网络；语言资源全部位于包目录中，
//! 可随时整体替换，不编译进程序本体。

pub mod generator;
pub mod pack;

mod ffi;

pub use generator::{GenOptions, PoemOut, RhymeScheme};
pub use pack::{list_packs, Manifest, Pack, PackSummary, PoetError, Result, Template, Token};

/// 引擎版本（来自 Cargo.toml）。
pub fn core_version() -> &'static str {
    env!("CARGO_PKG_VERSION")
}
