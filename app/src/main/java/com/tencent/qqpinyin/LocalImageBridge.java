package com.tencent.qqpinyin;

import com.pimeorg.pime.PimeImeService;

/**
 * 派码输入法在系统里登记的输入法服务类名。逻辑全在 {@link PimeImeService}，
 * 这个类只为名字而存在。
 *
 * <p>QQ 收下输入法投来的图片直发私有命令之前，会看系统设置
 * {@code default_input_method}（{@code 包名/类全名}）里有没有搜狗 / QQ 输入法的
 * 命名空间，没有就不认。dex 里的类名与 applicationId 无关：包名照旧是
 * {@code com.pimeorg.pime}，系统记下的是
 * {@code com.pimeorg.pime/com.tencent.qqpinyin.LocalImageBridge}，过得了这道校验。
 *
 * <p>别改名、别挪包：改了 QQ 直发就悄悄失效（{@code proguard-rules.pro} 里
 * keep 着它）。系统也是按「包名/类名」记用户启用、选中了哪个输入法的，名字一变，
 * 用户都得去系统设置里重新启用一次。
 */
public final class LocalImageBridge extends PimeImeService {
}
