package backend;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.Set;
import java.util.HashSet;
import java.util.Map;
import java.util.HashMap;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import java.nio.file.Path;
import java.nio.file.Paths;

public class BackendServer {
    private static List<String> texts = new ArrayList<>();
    private static List<String> textNames = new ArrayList<>();

    public static void main(String[] args) throws IOException {
        // Cargar los textos en memoria
        loadTexts();

        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/consulta", new ConsultaHandler());
        server.setExecutor(null);
        server.start();
        System.out.println("BackendServer running on port 8080");  // Mensaje de depuración
    }

    private static void loadTexts() throws IOException {
        Path resourceDirectory = Paths.get("src", "main", "resources", "texts");
        File folder = new File(resourceDirectory.toUri());
        File[] listOfFiles = folder.listFiles((dir, name) -> name.endsWith(".txt"));

        if (listOfFiles != null) {
            for (File file : listOfFiles) {
                String content = new String(Files.readAllBytes(Paths.get(file.getPath())));
                texts.add(content);
                textNames.add(file.getName().replace(".txt", ""));  // Guardar el nombre del libro sin la extensión
                System.out.println("Loaded text from: " + file.getPath());  // Mensaje de depuración
            }
        } else {
            System.out.println("No files found in texts folder.");  // Mensaje de depuración
        }
    }

    static class ConsultaHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String query = exchange.getRequestURI().getQuery();
            int n = Integer.parseInt(query.split("=")[1]);

            String[] servers = {
                "http://localhost:8090/procesar",
                "http://localhost:8091/procesar",
                "http://localhost:8092/procesar"
            };

            List<String> results = new ArrayList<>();
            List<Integer> activeServers = new ArrayList<>();
            long startTime = System.currentTimeMillis();

            // Verificar qué servidores están activos usando GET
            for (int i = 0; i < servers.length; i++) {
                try {
                    HttpURLConnection connection = (HttpURLConnection) new URL(servers[i] + "?n=" + n).openConnection();
                    connection.setRequestMethod("GET");
                    connection.setConnectTimeout(5000);  // Timeout de conexión: 5 segundos
                    connection.setReadTimeout(5000);  // Timeout de lectura: 5 segundos
                    int responseCode = connection.getResponseCode();
                    if (responseCode == 200) {
                        activeServers.add(i);
                    }
                } catch (IOException e) {
                    System.out.println("Server " + servers[i] + " is inactive.");
                }
            }

            // Redistribuir los textos entre los servidores activos
            int numActiveServers = activeServers.size();
            if (numActiveServers == 0) {
                System.out.println("No active servers available.");
                exchange.sendResponseHeaders(503, -1);  // Service Unavailable
                return;
            }

            int textsPerServer = (int) Math.ceil((double) texts.size() / numActiveServers);

            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (int i = 0; i < numActiveServers; i++) {
                final int serverIndex = i;  // Variable final para usar dentro de la lambda
                int start = serverIndex * textsPerServer;
                int end = Math.min(start + textsPerServer, texts.size());

                List<String> textSlice = texts.subList(start, end);
                List<String> textNameSlice = textNames.subList(start, end);

                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        HttpURLConnection connection = (HttpURLConnection) new URL(servers[activeServers.get(serverIndex)] + "?n=" + n).openConnection();
                        connection.setRequestMethod("POST");
                        connection.setDoOutput(true);
                        connection.setConnectTimeout(10000);  // Timeout de conexión: 10 segundos
                        connection.setReadTimeout(50000);  // Timeout de lectura: 50 segundos

                        // Enviar datos al servidor de procesamiento
                        OutputStream os = connection.getOutputStream();
                        os.write(serialize(textSlice, textNameSlice).getBytes());
                        os.close();

                        System.out.println("Connecting to " + servers[activeServers.get(serverIndex)]);  // Mensaje de depuración

                        if (connection.getResponseCode() == 200) {
                            Scanner scanner = new Scanner(connection.getInputStream());
                            while (scanner.hasNextLine()) {
                                synchronized (results) {
                                    results.add(scanner.nextLine());
                                }
                            }
                            scanner.close();
                            System.out.println("Received response from " + servers[activeServers.get(serverIndex)]);  // Mensaje de depuración
                        } else {
                            System.out.println("Error response from " + servers[activeServers.get(serverIndex)] + ": " + connection.getResponseCode());
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                        System.out.println("Failed to connect to " + servers[activeServers.get(serverIndex)]);  // Mensaje de depuración
                    }
                });

                futures.add(future);
            }

            // Esperar a que todas las tareas se completen
            CompletableFuture<Void> allFutures = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
            try {
                allFutures.get();
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }

            long endTime = System.currentTimeMillis();
            long duration = endTime - startTime;

            StringBuilder response = new StringBuilder();
            response.append("Numero de servidores activos: ").append(numActiveServers).append("\n");
            response.append("Tiempo de procesamiento: ").append(duration / 1000).append(" segundos con ").append(duration % 1000).append(" milisegundos\n");

            // Map to store phrases and the texts they appear in
            Map<String, Set<String>> phraseToTexts = new HashMap<>();
            for (String result : results) {
                String[] parts = result.split(" aparece en ");
                if (parts.length == 2) {
                    String phrase = parts[0].trim();
                    String[] texts = parts[1].split(" y ");
                    for (String text : texts) {
                        phraseToTexts.putIfAbsent(phrase, new HashSet<>());
                        phraseToTexts.get(phrase).add(text.trim());
                    }
                }
            }

            // Format the results
            for (Map.Entry<String, Set<String>> entry : phraseToTexts.entrySet()) {
                String phrase = entry.getKey();
                Set<String> texts = entry.getValue();
                response.append("\"").append(phrase).append("\" aparece en:\n");
                for (String text : texts) {
                    response.append("\t- \"").append(text).append("\"\n");
                }
                response.append("\n");
            }

            exchange.sendResponseHeaders(200, response.toString().getBytes().length);
            OutputStream os = exchange.getResponseBody();
            os.write(response.toString().getBytes());
            os.close();
        }

        private String serialize(List<String> texts, List<String> textNames) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < texts.size(); i++) {
                sb.append("##TEXT##").append(textNames.get(i)).append("##CONTENT##").append(texts.get(i)).append("##END##");
            }
            return sb.toString();
        }
    }
}
