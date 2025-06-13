package ca.uhn.fhir.jpa.starter;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;

@Interceptor
public class CustomForwardingInterceptor {

    private final RestTemplate restTemplate = new RestTemplate();

    @Hook(Pointcut.SERVER_INCOMING_REQUEST_POST_PROCESSED)
    public boolean handleAllRequests(RequestDetails requestDetails, IBaseResource resource) {
        try {
            if (!(requestDetails instanceof ServletRequestDetails)) {
                throw new InvalidRequestException("Not a ServletRequestDetails request");
            }

            ServletRequestDetails servletRequestDetails = (ServletRequestDetails) requestDetails;
            HttpServletRequest servletRequest = servletRequestDetails.getServletRequest();
            HttpServletResponse servletResponse = servletRequestDetails.getServletResponse();

            String method = servletRequest.getMethod();
            String path = servletRequest.getRequestURI();
            String query = servletRequest.getQueryString();

            String strippedPath = path.replaceFirst("^/fhir", "");
            String fullPath = query != null ? strippedPath + "?" + query : strippedPath;

            System.out.println("=== Intercepted HTTP Request ===");
            System.out.println("Method: " + method);
            System.out.println("Original Path: " + path);
            System.out.println("Forwarding Path: " + fullPath);

            if (method.equalsIgnoreCase("GET") &&
                (path.equals("/fhir/metadata") || path.equals("/") || path.equals("/fhir") || path.equals("/fhir/"))) {
                System.out.println("Bypassing forwarding for " + path);
                return true;
            }

            // Read the full request body once
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(servletRequest.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder requestBodyBuilder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                requestBodyBuilder.append(line);
            }
            String rawBody = requestBodyBuilder.toString();

            System.out.println("Request Body:");
            System.out.println(rawBody);

            // Collect headers but exclude Content-Length
            HttpHeaders headers = new HttpHeaders();
            Enumeration<String> headerNames = servletRequest.getHeaderNames();
            while (headerNames.hasMoreElements()) {
                String headerName = headerNames.nextElement();
                if (!headerName.equalsIgnoreCase(HttpHeaders.CONTENT_LENGTH)) {
                    Enumeration<String> headerValues = servletRequest.getHeaders(headerName);
                    while (headerValues.hasMoreElements()) {
                        headers.add(headerName, headerValues.nextElement());
                    }
                }
            }

            if (!headers.containsKey(HttpHeaders.CONTENT_TYPE)) {
                headers.setContentType(MediaType.APPLICATION_JSON);
            }

            System.out.println("Request Headers:");
            headers.forEach((k, v) -> System.out.println(k + ": " + v));

            HttpEntity<String> entity = new HttpEntity<>(rawBody, headers);
            String forwardUrl = "https://seir-api-1057985422230.asia-southeast1.run.app" + fullPath;
            HttpMethod httpMethod = HttpMethod.valueOf(method);

            if (httpMethod == null) {
                throw new InvalidRequestException("Unsupported HTTP method: " + method);
            }

            System.out.println("Forwarding request to downstream server:");
            System.out.println("URL: " + forwardUrl);
            System.out.println("HTTP Method: " + httpMethod);

            ResponseEntity<String> response = restTemplate.exchange(forwardUrl, httpMethod, entity, String.class);

            System.out.println("=== Response from downstream ===");
            System.out.println("Status Code: " + response.getStatusCodeValue());
            System.out.println("Response Headers:");
            response.getHeaders().forEach((k, v) -> System.out.println(k + ": " + v));
            System.out.println("Response Body:");
            System.out.println(response.getBody());

            String responseBody = response.getBody() != null ? response.getBody() : "";
            MediaType contentType = response.getHeaders().getContentType();

            servletResponse.setStatus(response.getStatusCodeValue());
            servletResponse.setContentType(contentType != null ? contentType.toString() : MediaType.APPLICATION_JSON_VALUE);
            servletResponse.getWriter().write(responseBody);
            servletResponse.getWriter().flush();
            servletResponse.getWriter().close();

            return false;

        } catch (Exception e) {
            System.out.println("=== Error occurred during forwarding ===");
            e.printStackTrace();
            throw new InvalidRequestException("Failed to forward request: " + e.getMessage(), e);
        }
    }
}