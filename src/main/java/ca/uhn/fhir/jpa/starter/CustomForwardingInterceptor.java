package ca.uhn.fhir.jpa.starter;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Map;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Interceptor
public class CustomForwardingInterceptor {

	private final RestTemplate restTemplate = new RestTemplate();
	private static final String TARGET_BASE_URL = "https://seir-api-128112993769.asia-southeast1.run.app";

	@Hook(Pointcut.SERVER_INCOMING_REQUEST_POST_PROCESSED)
	public boolean handleAllRequests(RequestDetails requestDetails, IBaseResource resource) {
		try {
			if (!(requestDetails instanceof ServletRequestDetails servletRequestDetails)) {
				throw new InvalidRequestException("Not a ServletRequestDetails request");
			}

			HttpServletRequest servletRequest = servletRequestDetails.getServletRequest();
			HttpServletResponse servletResponse = servletRequestDetails.getServletResponse();

			String method = servletRequest.getMethod();
			String path = servletRequest.getRequestURI();
			String strippedPath = path.replaceFirst("^/fhir", "");

			// Handle GET query string manually (supports multiple values)
			String query = reconstructQueryString(servletRequest);
			String fullPath = query.isEmpty() ? strippedPath : strippedPath + "?" + query;

			// Allow GET to /metadata and root paths to pass through
			if (method.equalsIgnoreCase("GET") &&
				(path.equals("/") || path.equals("/fhir") || path.equals("/fhir/") || path.equals("/fhir/metadata"))) {
				System.out.println("Bypassing forwarding for " + path);
				return true;
			}

			System.out.println("=== Intercepted HTTP Request ===");
			System.out.println("Method: " + method);
			System.out.println("Original Path: " + path);
			System.out.println("Forwarding Path: " + fullPath);

			// Read body (for POST, PUT, etc.)
			BufferedReader reader = new BufferedReader(
				new InputStreamReader(servletRequest.getInputStream(), StandardCharsets.UTF_8));
			StringBuilder requestBodyBuilder = new StringBuilder();
			String line;
			while ((line = reader.readLine()) != null) {
				requestBodyBuilder.append(line);
			}
			String rawBody = requestBodyBuilder.toString();

			System.out.println("Request Body:\n" + rawBody);

			// Collect headers (excluding Content-Length)
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
			HttpMethod httpMethod = HttpMethod.valueOf(method.toUpperCase());
			if (httpMethod == null) {
				throw new InvalidRequestException("Unsupported HTTP method: " + method);
			}

			String forwardUrl = TARGET_BASE_URL + fullPath;
			System.out.println("Forwarding request to downstream:");
			System.out.println("URL: " + forwardUrl);
			System.out.println("HTTP Method: " + httpMethod);

			ResponseEntity<String> response = restTemplate.exchange(forwardUrl, httpMethod, entity, String.class);

			System.out.println("=== Response from downstream ===");
			System.out.println("Status Code: " + response.getStatusCodeValue());
			System.out.println("Response Headers:");
			response.getHeaders().forEach((k, v) -> System.out.println(k + ": " + v));
			System.out.println("Response Body:\n" + response.getBody());

			servletResponse.setStatus(response.getStatusCodeValue());
			MediaType contentType = response.getHeaders().getContentType();
			servletResponse.setContentType(contentType != null ? contentType.toString() : MediaType.APPLICATION_JSON_VALUE);
			servletResponse.getWriter().write(response.getBody() != null ? response.getBody() : "");
			servletResponse.getWriter().flush();
			servletResponse.getWriter().close();

			return false; // stop further HAPI FHIR processing
		} catch (HttpStatusCodeException ex) {
			String errorBody = ex.getResponseBodyAsString();
			HttpStatus statusCode = (HttpStatus) ex.getStatusCode();

			if (statusCode == HttpStatus.UNAUTHORIZED) {
				errorBody = "Authentication failed: Invalid credentials";
			}
			System.out.println("=== Error occurred during forwarding ===");
			ex.printStackTrace();

			throw new InvalidRequestException("Failed to forward request: " + statusCode + ": " + errorBody);

		} catch (Exception e) {
			System.out.println("=== Error occurred during forwarding ===");
			e.printStackTrace();

			throw new InvalidRequestException("Failed to forward request: " + e.getMessage(), e);
		}
	}

	private String reconstructQueryString(HttpServletRequest request) {
		StringBuilder result = new StringBuilder();
		for (Map.Entry<String, String[]> entry : request.getParameterMap().entrySet()) {
			String key = entry.getKey();
			for (String value : entry.getValue()) {
				if (result.length() > 0) result.append("&");
				result.append(key).append("=").append(value);
			}
		}
		return result.toString();
	}
}
