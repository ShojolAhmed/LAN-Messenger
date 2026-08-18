package com.lanmessenger.client.ui;

import com.lanmessenger.client.net.ConnectionValidator;
import com.lanmessenger.common.Protocol;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * The connection / login screen shown when the app starts.
 *
 * <p>A single centered card collects a <b>Username</b>, <b>Server IP</b> and
 * <b>Port</b> and offers a <b>Connect</b> button. It is purely a view: it renders
 * the form, surfaces validation errors, and switches into a subtle "connecting"
 * state, but it performs no validation or networking itself. The owning
 * {@link com.lanmessenger.client.ClientController} reads the field values via
 * {@link #username()}, {@link #host()} and {@link #portText()}, validates them
 * with {@link ConnectionValidator}, and drives the view through
 * {@link #showError(String, ConnectionValidator.Field)},
 * {@link #clearError()} and {@link #setConnecting(boolean)}.
 *
 * <p>Every colour and state lives in {@code theme.css}; this class only assigns
 * style classes, consistent with the rest of the design system. Submitting is
 * wired to <b>Enter</b> in any field as well as the button, via
 * {@link #setOnConnect(Runnable)}.
 */
public final class ConnectView extends StackPane {

    private final TextField usernameField = new TextField();
    private final TextField hostField = new TextField();
    private final TextField portField = new TextField();

    private final Label errorLabel = new Label();
    private final Button connectButton = new Button("Connect");
    private final ProgressIndicator spinner = new ProgressIndicator();

    private Runnable onConnect = () -> { };
    private boolean connecting;

    public ConnectView() {
        getStyleClass().add("connect-root");
        setAlignment(Pos.CENTER);

        getChildren().add(buildCard());

        // Editing any field clears a previous error so stale highlights don't linger.
        usernameField.textProperty().addListener((obs, old, now) -> clearError());
        hostField.textProperty().addListener((obs, old, now) -> clearError());
        portField.textProperty().addListener((obs, old, now) -> clearError());
    }

    private VBox buildCard() {
        VBox card = new VBox();
        card.getStyleClass().add("connect-card");
        card.setAlignment(Pos.TOP_LEFT);

        card.getChildren().addAll(
                buildBrand(),
                heading("Welcome back", "connect-heading"),
                heading("Connect to your LAN server to start chatting.", "connect-sub"),
                fieldGroup("Username", usernameField, "e.g. Shojol"),
                fieldGroup("Server IP", hostField, "e.g. 192.168.0.100"),
                fieldGroup("Port", portField, "e.g. " + Protocol.DEFAULT_PORT),
                buildError(),
                buildButton());

        // Sensible defaults so a local test connection is one field away.
        hostField.setText("127.0.0.1");
        portField.setText(String.valueOf(Protocol.DEFAULT_PORT));

        return card;
    }

    private HBox buildBrand() {
        Label glyph = new Label("L");
        glyph.getStyleClass().add("brand-glyph");
        StackPane mark = new StackPane(glyph);
        mark.getStyleClass().add("brand-mark");

        Label name = new Label(Protocol.APP_NAME);
        name.getStyleClass().add("brand-text");

        HBox brand = new HBox(mark, name);
        brand.getStyleClass().add("connect-brand");
        brand.setAlignment(Pos.CENTER_LEFT);
        return brand;
    }

    private Label heading(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        label.setWrapText(true);
        return label;
    }

    private VBox fieldGroup(String labelText, TextField field, String prompt) {
        Label label = new Label(labelText);
        label.getStyleClass().add("field-label");

        field.getStyleClass().addAll("field-input", "text-input");
        field.setPromptText(prompt);
        field.setAccessibleText(labelText); // associate the visible label for screen readers
        field.setOnAction(event -> fire()); // Enter submits from any field

        VBox group = new VBox(label, field);
        group.getStyleClass().add("field-group");
        return group;
    }

    private Label buildError() {
        errorLabel.getStyleClass().add("connect-error");
        errorLabel.setWrapText(true);
        errorLabel.setVisible(false);
        errorLabel.setManaged(false); // take no vertical space until shown
        return errorLabel;
    }

    private VBox buildButton() {
        spinner.getStyleClass().add("connect-spinner");
        spinner.setPrefSize(16, 16);
        spinner.setMaxSize(16, 16);

        connectButton.getStyleClass().add("connect-button");
        connectButton.setMaxWidth(Double.MAX_VALUE);
        connectButton.setOnAction(event -> fire());

        VBox wrapper = new VBox(connectButton);
        wrapper.getStyleClass().add("connect-button-row");
        VBox.setVgrow(connectButton, Priority.NEVER);
        return wrapper;
    }

    // ---------------------------------------------------------------------
    // Public API used by the controller
    // ---------------------------------------------------------------------

    /** Registers the callback invoked when the user submits the form. */
    public void setOnConnect(Runnable callback) {
        this.onConnect = callback == null ? () -> { } : callback;
    }

    /** @return the raw username text as typed. */
    public String username() {
        return usernameField.getText();
    }

    /** @return the raw server IP/host text as typed. */
    public String host() {
        return hostField.getText();
    }

    /** @return the raw port text as typed. */
    public String portText() {
        return portField.getText();
    }

    /** Shows a validation/connection error and highlights the offending field. */
    public void showError(String message, ConnectionValidator.Field field) {
        errorLabel.setText(message);
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
        markInvalid(field);
    }

    /** Convenience overload that shows an error not tied to a specific field. */
    public void showError(String message) {
        showError(message, ConnectionValidator.Field.NONE);
    }

    /** Hides any current error and removes invalid highlights. */
    public void clearError() {
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
        errorLabel.setText("");
        usernameField.getStyleClass().remove("field-invalid");
        hostField.getStyleClass().remove("field-invalid");
        portField.getStyleClass().remove("field-invalid");
    }

    /**
     * Switches the card into (or out of) the connecting state: fields and the
     * button are disabled and the button shows a small spinner with "Connecting…",
     * so the user gets clear feedback while the attempt runs on a background thread.
     */
    public void setConnecting(boolean connecting) {
        this.connecting = connecting;
        usernameField.setDisable(connecting);
        hostField.setDisable(connecting);
        portField.setDisable(connecting);
        connectButton.setDisable(connecting);
        if (connecting) {
            connectButton.setText("Connecting\u2026");
            connectButton.setGraphic(spinner);
        } else {
            connectButton.setText("Connect");
            connectButton.setGraphic(null);
        }
    }

    /** Moves keyboard focus to the username field. */
    public void focusUsername() {
        usernameField.requestFocus();
    }

    private void markInvalid(ConnectionValidator.Field field) {
        TextField target = switch (field) {
            case USERNAME -> usernameField;
            case HOST -> hostField;
            case PORT -> portField;
            case NONE -> null;
        };
        if (target != null && !target.getStyleClass().contains("field-invalid")) {
            target.getStyleClass().add("field-invalid");
        }
    }

    private void fire() {
        if (!connecting) {
            onConnect.run();
        }
    }
}
