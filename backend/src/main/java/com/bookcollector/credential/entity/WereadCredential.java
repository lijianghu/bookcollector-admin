package com.bookcollector.credential.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.mapping.Document;

import java.io.Serializable;
import java.util.Date;

/**
 * 接口凭证（集合 {@code weread_credential}，<b>单文档</b>）。
 *
 * <h3>为什么放进库里，而不是写死在代码 / yml 里</h3>
 * 两个基础地址与登录态都属于「会变、且不该进版本库」的东西：登录态换一次就失效，
 * 书单接口的域名也可能调整。写死在代码里的话，改一次就得重新打包 ——
 * 而本项目没有 CI，改代码就等于重新部署。
 *
 * <p>程序只在<b>启动时</b>读一次进内存，之后每个请求都用内存副本；
 * 页面上点「保存」会同时更新库与内存（见 {@code WereadCredentialStore}）。
 *
 * <h3>🔴 敏感字段</h3>
 * {@link #skey} 与 {@link #synckey} 加了 {@link ToString#Exclude}：日志只允许打
 * 「已配置 / 未配置」这种不含内容的形式，注释里也不写值。
 *
 * <h3>🔴 2026-10-15：App 端请求头七项 + synckey 全部挪进库</h3>
 * 原来 {@code channelid / vid / priority / accept-language / basever / v}
 * 六个值是硬编码在 {@code WereadHeaders} 里的（抄自一次抓包）。用户实测确认：
 * <b>书单请求不带 URL 参数 {@code synckey} 时，服务端返回的 books 元素是拍平的</b>
 * （没有 {@code bookInfo} 包装、连 {@code intro} 都缺）；带上 synckey 才是完整形状。
 * 所以这七项连同 synckey 一起成为「书单凭证」——缺任何一项书单链路都不可用，
 * 校验见 {@code WereadCredentialStore#requireBooklistComplete()}，
 * 界面入口在「书单配置」页顶部表单（用户 2026-10-15 要求：可回显、明文保存，
 * 但绝不打进注释与日志）。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "weread_credential")
public class WereadCredential implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 固定主键，全表只有这一条 */
    @Id
    private String id;

    /**
     * 分类 / 榜单接口的基础地址（url1）。
     *
     * <p>首次启动时以 {@code application.yml} 里那份既有配置作为初值 ——
     * 这样「用户还没保存过凭证」不会把已经在跑的分类 / 榜单采集打挂。
     */
    private String listUrl;

    /** 书单接口的基础地址（url2）。用户从页面保存 */
    private String booklistUrl;

    /** ★ 敏感：登录态 skey。绝不进日志、注释里也不写值 */
    @ToString.Exclude
    private String skey;

    /**
     * ★ 书单请求的 URL 参数 {@code synckey}（同步键，Unix 秒级时间戳）。
     *
     * <p>🔴 2026-10-15 晚起：<b>不手工配置，也不从响应回读</b>（表单已去掉该项）。
     * 用户原话：「synckey 不是从 root.synckey 取，而是后端 new Date() 的秒值」——
     * 每次书单请求发出前由 {@code WereadCredentialStore#refreshSynckeyNow()}
     * 按本地时钟生成并刷新到这里。所以库里这个值 = <b>最近一次书单请求用的值</b>，
     * 只作观测（表单下方只读展示 + 与请求审计对照）。
     *
     * <p>用户本地实测：书单 URL 带它 → {@code books} 元素是带 {@code bookInfo}
     * 的完整形状；不带 → 拍平（缺 {@code bookInfo}、缺 {@code intro}）。
     * 它本身是时间戳而非令牌，每次请求现取，不存在过期问题。
     */
    @ToString.Exclude
    private String synckey;

    /** App 端请求头 {@code channelid}。见 {@code WereadHeaders#appHeaders} */
    private String channelid;

    /** App 端请求头 {@code vid}（用户 vid，与 skey 属于同一账号） */
    private String vid;

    /** App 端请求头 {@code priority} */
    private String priority;

    /** App 端请求头 {@code accept-language}。⚠️ Java 字段名叫 {@code acceptLanguage}，库里同 */
    private String acceptLanguage;

    /** App 端请求头 {@code basever}（客户端版本号） */
    private String basever;

    /** App 端请求头 {@code v}（客户端版本号，与 basever 同值） */
    private String v;

    @LastModifiedDate
    private Date updatedAt;
}
