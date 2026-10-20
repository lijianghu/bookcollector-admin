package com.bookcollector.booklist.req;

import lombok.Data;

import java.io.Serializable;

/**
 * 手工新增书单的请求（2026-10-20）。
 *
 * <p>{@code booklistId} 必填（形如 {@code 352003193_8cKur0WC3}）；
 * {@code name} 可空，空时列表按「待回填」显示，等同步/采集时回填。
 */
@Data
public class BooklistCreateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private String booklistId;

    private String name;
}
