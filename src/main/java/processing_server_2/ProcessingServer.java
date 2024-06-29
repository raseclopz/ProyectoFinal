package processing_server_2;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import java.util.Scanner;

public class ProcessingServer {
    private static Set<String> texts = new HashSet<>();
    private static Set<String> textNames = new HashSet<>();

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(args[0]);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/procesar", new ProcesarHandler());
        server.setExecutor(null);
        server.start();
        System.out.println("ProcessingServer running on port " + port);
    }

    static class ProcesarHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(200, -1);
                return;
            }

            if ("POST".equals(exchange.getRequestMethod())) {
                texts.clear();
                textNames.clear();
                Scanner scanner = new Scanner(exchange.getRequestBody()).useDelimiter("##END##");
                while (scanner.hasNext()) {
                    String textBlock = scanner.next();
                    if (textBlock.startsWith("##TEXT##")) {
                        String[] parts = textBlock.split("##CONTENT##");
                        String textName = parts[0].replace("##TEXT##", "").trim();
                        String textContent = parts[1].trim();
                        texts.add(textContent);
                        textNames.add(textName);
                    }
                }
                scanner.close();
            }

            String query = exchange.getRequestURI().getQuery();
            int n = Integer.parseInt(query.split("=")[1]);

            System.out.println("Received request with n=" + n);  // Mensaje de depuración

            Set<String> results = new ConcurrentSkipListSet<>();
            long startTime = System.currentTimeMillis();

            String[] textsArray = texts.toArray(new String[0]);
            String[] textNamesArray = textNames.toArray(new String[0]);

            for (int i = 0; i < textsArray.length; i++) {
                for (int j = i + 1; j < textsArray.length; j++) {
                    System.out.println("Comparing texts " + textNamesArray[i] + " and " + textNamesArray[j]);  // Mensaje de depuración
                    results.addAll(findCommonPhrases(textsArray[i], textNamesArray[i], textsArray[j], textNamesArray[j], n));
                }
            }

            long endTime = System.currentTimeMillis();
            long duration = endTime - startTime;
            System.out.println("Processing completed in " + duration + " milliseconds");  // Mensaje de depuración

            StringBuilder response = new StringBuilder();
            for (String result : results) {
                response.append(result).append("\n");
            }

            exchange.sendResponseHeaders(200, response.toString().getBytes().length);
            OutputStream os = exchange.getResponseBody();
            os.write(response.toString().getBytes());
            os.close();

            System.out.println("Sent response with " + results.size() + " results");  // Mensaje de depuración
        }

        private Set<String> findCommonPhrases(String text1, String textName1, String text2, String textName2, int n) {
            Set<String> commonPhrases = new ConcurrentSkipListSet<>();
            Set<String> phrases1 = extractPhrases(text1, n);
            Set<String> phrases2 = extractPhrases(text2, n);

            System.out.println("Extracted " + phrases1.size() + " phrases from " + textName1);
            System.out.println("Extracted " + phrases2.size() + " phrases from " + textName2);

            long comparisonStartTime = System.currentTimeMillis();

            for (String phrase1 : phrases1) {
                if (phrases2.contains(phrase1)) {
                    commonPhrases.add(phrase1 + " aparece en " + textName1 + " y " + textName2);
                }
            }

            long comparisonEndTime = System.currentTimeMillis();
            long comparisonDuration = comparisonEndTime - comparisonStartTime;
            System.out.println("Comparing phrases took " + comparisonDuration + " milliseconds");

            return commonPhrases;
        }

        private Set<String> extractPhrases(String text, int n) {
            text = text.replaceAll("[^a-zA-Z0-9\\s]", "").toLowerCase();
            String[] words = text.split("\\s+");

            System.out.println("Extracting phrases from text with " + words.length + " words");

            return IntStream.range(0, words.length - n + 1)
                            .mapToObj(i -> String.join(" ", IntStream.range(i, i + n)
                                                                     .mapToObj(j -> words[j])
                                                                     .collect(Collectors.toList())))
                            .collect(Collectors.toSet());
        }
    }
}
