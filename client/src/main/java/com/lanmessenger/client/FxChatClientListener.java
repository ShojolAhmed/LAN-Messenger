package com.lanmessenger.client;

import com.lanmessenger.client.net.ChatClientListener;
import com.lanmessenger.common.Message;
import javafx.application.Platform;

import java.util.Objects;

/**
 * The bridge between the background networking layer and the JavaFX UI.
 *
 * <p>{@link com.lanmessenger.client.net.ChatClient} invokes its
 * {@link ChatClientListener} from background threads (the reader/sender threads).
 * JavaFX controls, however, may only be touched from the JavaFX Application
 * Thread. This decorator wraps the UI's real listener and re-dispatches every
 * callback through {@link Platform#runLater(Runnable)}, so the wrapped handler
 * always runs on the FX thread and can update controls directly and safely.
 *
 * <p>This is the <em>only</em> seam where networking and JavaFX meet: the entire
 * {@code net} package stays free of JavaFX, and the UI stays free of threading
 * concerns. Typical usage:
 *
 * <pre>{@code
 * ChatClientListener uiHandler = new ChatClientListener() {
 *     public void onMessage(Message m) { chatView.append(m); }   // FX-thread safe
 *     public void onDisconnected(String r) { statusLabel.setText(r); }
 * };
 * ChatClient client = new ChatClient(new FxChatClientListener(uiHandler));
 * }</pre>
 */
public final class FxChatClientListener implements ChatClientListener {

    private final ChatClientListener delegate;

    /**
     * @param delegate the UI listener whose callbacks must run on the FX thread
     */
    public FxChatClientListener(ChatClientListener delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public void onConnected() {
        Platform.runLater(delegate::onConnected);
    }

    @Override
    public void onMessage(Message message) {
        Platform.runLater(() -> delegate.onMessage(message));
    }

    @Override
    public void onDisconnected(String reason) {
        Platform.runLater(() -> delegate.onDisconnected(reason));
    }

    @Override
    public void onError(Throwable error) {
        Platform.runLater(() -> delegate.onError(error));
    }
}
