package com.project;

import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.event.ActionEvent;
import javafx.application.Platform;
import java.net.URL;
import java.util.ResourceBundle;
import javafx.fxml.Initializable;
import javafx.geometry.Insets;
import javafx.geometry.Pos;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpRequest.BodyPublishers;
import java.nio.charset.StandardCharsets;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.stage.FileChooser;

import org.json.JSONArray;
import org.json.JSONObject;

public class Controller implements Initializable {

    // Models
    private static final String TEXT_MODEL   = "gemma3:1b";
    private static final String VISION_MODEL = "llava-phi3";

    @FXML private Button send, submit, cancel, clip,limpiar;
    // @FXML private Text textInfo;
    @FXML private VBox chatBox;
    @FXML private TextField textfi;
    @FXML private ScrollPane scrollPane;


    private final HttpClient httpClient = HttpClient.newHttpClient();
    private CompletableFuture<HttpResponse<InputStream>> streamRequest;
    private CompletableFuture<HttpResponse<String>> completeRequest;
    private final AtomicBoolean isCancelled = new AtomicBoolean(false);
    private InputStream currentInputStream;
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private Future<?> streamReadingTask;
    private volatile boolean isFirst = false;

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        setButtonsIdle();
    }

    // --- UI actions ---

    private void addUserMessage(String msg) {
        Platform.runLater(() -> {
            Label bubble = new Label(msg);
            bubble.setWrapText(true);
            bubble.setStyle(
                "-fx-background-color: #DCF8C6;" +   // verde claro estilo WhatsApp
                "-fx-padding: 10;" +
                "-fx-background-radius: 10;" +
                "-fx-font-size: 14px;"
            );

            HBox container = new HBox(bubble);
            container.setAlignment(Pos.CENTER_RIGHT);   // alineado a la derecha
            container.setPadding(new Insets(5, 5, 5, 5));

            chatBox.getChildren().add(container);
            scrollPane.setVvalue(1.0);
        });
    }

    private void addAssistantMessage(String msg) {
        Platform.runLater(() -> {
            Label bubble = new Label(msg);
            bubble.setWrapText(true);
            bubble.setStyle(
                "-fx-background-color: #FFFFFF;" +   // blanco estilo ChatGPT
                "-fx-padding: 10;" +
                "-fx-background-radius: 10;" +
                "-fx-font-size: 14px;" +
                "-fx-border-color: #E0E0E0;" +
                "-fx-border-radius: 10;"
            );

            HBox container = new HBox(bubble);
            container.setAlignment(Pos.CENTER_LEFT);   // alineado a la izquierda
            container.setPadding(new Insets(5, 5, 5, 5));

            chatBox.getChildren().add(container);
            scrollPane.setVvalue(1.0);
        });
    }

    private void addSystemMessage(String msg) {
        Platform.runLater(() -> {
            Label bubble = new Label(msg);
            bubble.setWrapText(true);
            bubble.setStyle(
                "-fx-background-color: #EEEEEE;" +
                "-fx-padding: 8;" +
                "-fx-background-radius: 8;" +
                "-fx-font-size: 12px;" +
                "-fx-text-fill: #555555;"
            );

            HBox container = new HBox(bubble);
            container.setAlignment(Pos.CENTER);   // centrado
            container.setPadding(new Insets(5, 5, 5, 5));

            chatBox.getChildren().add(container);
            scrollPane.setVvalue(1.0);
        });
    }



    @FXML
    private void callStream(ActionEvent event) {
        // 1. Obtener el texto del usuario
        String prompt = textfi.getText().trim();
        if (prompt.isEmpty()) return;

        // 2. Mostrar el mensaje del usuario en el chat
        addUserMessage(prompt);

        // 3. Limpiar el campo de texto
        textfi.clear();

        // 4. Preparar el sistema
        addSystemMessage("Thinking...");
        setButtonsRunning();
        isCancelled.set(false);

        // 5. Cargar modelo y ejecutar con el texto del usuario
        ensureModelLoaded(TEXT_MODEL).whenComplete((v, err) -> {
            if (err != null) {
                Platform.runLater(() -> { 
                    addSystemMessage("Error loading model."); 
                    setButtonsIdle(); 
                });
                return;
            }

            // 6. Llamar al modelo con el prompt real del usuario
            executeTextRequest(TEXT_MODEL, prompt, true);
        });
    }


    @FXML
    private void callComplete(ActionEvent event) {
        addSystemMessage("");
        setButtonsRunning();
        isCancelled.set(false);

        ensureModelLoaded(TEXT_MODEL).whenComplete((v, err) -> {
            if (err != null) {
                Platform.runLater(() -> { addSystemMessage("Error loading model."); setButtonsIdle(); });
                return;
            }
            executeTextRequest(TEXT_MODEL, "Tell me a haiku.", false);
        });
    }

    @FXML
    private void callPicture(ActionEvent event) {
        addSystemMessage("");
        setButtonsRunning();
        isCancelled.set(false);

        // Choose image file
        FileChooser fc = new FileChooser();
        fc.setTitle("Choose an image");
        fc.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg", "*.webp", "*.bmp", "*.gif")
        );

        // set default dir to current working directory
        File initialDir = new File(System.getProperty("user.dir"));
        if (initialDir.exists() && initialDir.isDirectory()) {
            fc.setInitialDirectory(initialDir);
        }

        File file = fc.showOpenDialog(clip.getScene().getWindow());
        if (file == null) {
            Platform.runLater(() -> { addSystemMessage("No file selected."); setButtonsIdle(); });
            return;
        }


        // Read file -> base64
        final String base64Image;
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            base64Image = Base64.getEncoder().encodeToString(bytes);
        } catch (Exception e) {
            e.printStackTrace();
            Platform.runLater(() -> { addSystemMessage("Error reading image."); setButtonsIdle(); });
            return;
        }

        ensureModelLoaded(VISION_MODEL).whenComplete((v, err) -> {
            if (err != null) {
                Platform.runLater(() -> { addSystemMessage("Error loading model."); setButtonsIdle(); });
                return;
            }
            executeImageRequest(VISION_MODEL, "Describe what's in this picture", base64Image);
        });
    }

    @FXML
    private void callBreak(ActionEvent event) {
        isCancelled.set(true);
        cancelStreamRequest();
        cancelCompleteRequest();
        Platform.runLater(() -> {
            addSystemMessage("Request cancelled.");
            setButtonsIdle();
        });
    }

    // --- Request helpers ---

    // Text-only (stream or not)
    private void executeTextRequest(String model, String prompt, boolean stream) {
        JSONObject body = new JSONObject()
            .put("model", model)
            .put("prompt", prompt)
            .put("stream", stream)
            .put("keep_alive", "10m");

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:11434/api/generate"))
            .header("Content-Type", "application/json")
            .POST(BodyPublishers.ofString(body.toString()))
            .build();

        if (stream) {
            Platform.runLater(() -> addSystemMessage("Wait stream ... " + prompt));
            isFirst = true;

            streamRequest = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                .thenApply(response -> {
                    currentInputStream = response.body();
                    streamReadingTask = executorService.submit(this::handleStreamResponse);
                    return response;
                })
                .exceptionally(e -> {
                    if (!isCancelled.get()) e.printStackTrace();
                    Platform.runLater(this::setButtonsIdle);
                    return null;
                });

        } else {
            Platform.runLater(() -> addSystemMessage("Wait complete ..."));

            completeRequest = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    String responseText = safeExtractTextResponse(response.body());
                    Platform.runLater(() -> { addSystemMessage(responseText); setButtonsIdle(); });
                    return response;
                })
                .exceptionally(e -> {
                    if (!isCancelled.get()) e.printStackTrace();
                    Platform.runLater(this::setButtonsIdle);
                    return null;
                });
        }
    }

    // Image + prompt (non-stream) using vision model
    private void executeImageRequest(String model, String prompt, String base64Image) {
        Platform.runLater(() -> addSystemMessage("Analyzing picture ..."));

        JSONObject body = new JSONObject()
            .put("model", model)
            .put("prompt", prompt)
            .put("images", new JSONArray().put(base64Image))
            .put("stream", false)
            .put("keep_alive", "10m")
            .put("options", new JSONObject()
                .put("num_ctx", 2048)     // lower context to reduce memory
                .put("num_predict", 256)  // shorter output to avoid OOM
            );

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:11434/api/generate"))
            .header("Content-Type", "application/json")
            .POST(BodyPublishers.ofString(body.toString()))
            .build();

        completeRequest = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(resp -> {
                int code = resp.statusCode();
                String bodyStr = resp.body();

                String msg = tryParseAnyMessage(bodyStr);
                if (msg == null || msg.isBlank()) {
                    msg = (code >= 200 && code < 300) ? "(empty response)" : "HTTP " + code + ": " + bodyStr;
                }

                final String toShow = msg;
                Platform.runLater(() -> { 
                    addSystemMessage(toShow); setButtonsIdle(); 
                    scrollPane.setVvalue(1.0);
                });
                return resp;
            })
            .exceptionally(e -> {
                if (!isCancelled.get()) e.printStackTrace();
                Platform.runLater(() -> { 
                    addSystemMessage("Request failed."); setButtonsIdle(); 
                    scrollPane.setVvalue(1.0);
                });
                return null;
            });
    }

    // Stream reader for text responses
    private void handleStreamResponse() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(currentInputStream, StandardCharsets.UTF_8))) {

            HBox assistantBubble = null;
            Label assistantLabel = null;

            String line;
            while ((line = reader.readLine()) != null) {
                if (isCancelled.get()) break;
                if (line.isBlank()) continue;

                JSONObject jsonResponse = new JSONObject(line);
                String chunk = jsonResponse.optString("response", "");
                if (chunk.isEmpty()) continue;

                if (assistantBubble == null) {
                    // Primer chunk → crear burbuja de IA
                    assistantLabel = new Label(chunk);
                    assistantLabel.setWrapText(true);
                    assistantLabel.setStyle(
                        "-fx-background-color: #FFFFFF;" +
                        "-fx-padding: 10;" +
                        "-fx-background-radius: 10;" +
                        "-fx-font-size: 14px;" +
                        "-fx-border-color: #E0E0E0;" +
                        "-fx-border-radius: 10;"
                    );

                    assistantBubble = new HBox(assistantLabel);
                    assistantBubble.setAlignment(Pos.CENTER_LEFT);
                    assistantBubble.setPadding(new Insets(5, 5, 5, 5));

                    HBox finalBubble = assistantBubble;
                    Platform.runLater(() -> {
                        chatBox.getChildren().add(finalBubble);
                        scrollPane.setVvalue(1.0);
                    });

                } else {
                    // Siguientes chunks → añadir texto a la misma burbuja
                    Label finalLabel = assistantLabel;
                    Platform.runLater(() ->{ 
                        finalLabel.setText(finalLabel.getText() + chunk);
                        scrollPane.setVvalue(1.0);
                    });
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
            Platform.runLater(() -> {
                addSystemMessage("Error during streaming.");
                setButtonsIdle();
            });
        } finally {
            try { if (currentInputStream != null) currentInputStream.close(); } catch (Exception ignore) {}
            Platform.runLater(this::setButtonsIdle);
        }
    }


    // --- Small utils ---

    private String safeExtractTextResponse(String bodyStr) {
        // Extract "response" or fallback to error/message if present
        try {
            JSONObject o = new JSONObject(bodyStr);
            String r = o.optString("response", null);
            if (r != null && !r.isBlank()) return r;
            if (o.has("message")) return o.optString("message");
            if (o.has("error"))   return "Error: " + o.optString("error");
        } catch (Exception ignore) {}
        return bodyStr != null && !bodyStr.isBlank() ? bodyStr : "(empty)";
    }

    private String tryParseAnyMessage(String bodyStr) {
        try {
            JSONObject o = new JSONObject(bodyStr);
            if (o.has("response")) return o.optString("response", "");
            if (o.has("message"))  return o.optString("message", "");
            if (o.has("error"))    return "Error: " + o.optString("error", "");
        } catch (Exception ignore) {}
        return null;
    }

    private void cancelStreamRequest() {
        if (streamRequest != null && !streamRequest.isDone()) {
            try {
                if (currentInputStream != null) {
                    System.out.println("Cancelling InputStream");
                    currentInputStream.close();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            System.out.println("Cancelling StreamRequest");
            if (streamReadingTask != null) {
                streamReadingTask.cancel(true);
            }
            streamRequest.cancel(true);
        }
    }

    private void cancelCompleteRequest() {
        if (completeRequest != null && !completeRequest.isDone()) {
            System.out.println("Cancelling CompleteRequest");
            completeRequest.cancel(true);
        }
    }

    private void setButtonsRunning() {
        send.setDisable(true);
        submit.setDisable(true);
        clip.setDisable(true);
        cancel.setDisable(false);
    }

    private void setButtonsIdle() {
        send.setDisable(false);
        submit.setDisable(false);
        clip.setDisable(false);
        cancel.setDisable(true);
        streamRequest = null;
        completeRequest = null;
    }

    // Ensure given model is in memory; preload if needed
    private CompletableFuture<Void> ensureModelLoaded(String modelName) {
        return httpClient.sendAsync(
                HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:11434/api/ps"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            )
            .thenCompose(resp -> {
                boolean loaded = false;
                try {
                    JSONObject o = new JSONObject(resp.body());
                    JSONArray models = o.optJSONArray("models");
                    if (models != null) {
                        for (int i = 0; i < models.length(); i++) {
                            String name = models.getJSONObject(i).optString("name", "");
                            String model = models.getJSONObject(i).optString("model", "");
                            if (name.startsWith(modelName) || model.startsWith(modelName)) { loaded = true; break; }
                        }
                    }
                } catch (Exception ignore) {}

                if (loaded) return CompletableFuture.completedFuture(null);

                Platform.runLater(() -> addSystemMessage("Loading model ..."));

                String preloadJson = new JSONObject()
                    .put("model", modelName)
                    .put("stream", false)
                    .put("keep_alive", "10m")
                    .toString();

                HttpRequest preloadReq = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:11434/api/generate"))
                    .header("Content-Type", "application/json")
                    .POST(BodyPublishers.ofString(preloadJson))
                    .build();

                return httpClient.sendAsync(preloadReq, HttpResponse.BodyHandlers.ofString())
                        .thenAccept(r -> { /* warmed */ });
            });
    }

    @FXML
    private void clearChat(ActionEvent event) {
         Platform.runLater(() -> {
             
             chatBox.getChildren().clear();
        });
    }

}
