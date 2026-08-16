package com.lanmessenger.client.ui.model;

/**
 * Distinguishes the two kinds of conversation the UI can show.
 *
 * <ul>
 *   <li>{@link #GLOBAL} &mdash; the shared room every user is in;</li>
 *   <li>{@link #DIRECT} &mdash; a one-to-one conversation with a single peer.</li>
 * </ul>
 */
public enum ConversationKind { GLOBAL, DIRECT }
