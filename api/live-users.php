<?php
declare(strict_types=1);
require_once __DIR__ . '/activity-store.php';
header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');
try {
    $method = $_SERVER['REQUEST_METHOD'];
    if ($method === 'GET') {
        echo json_encode(fm_activity(), JSON_THROW_ON_ERROR);
    } elseif ($method === 'POST') {
        if ((int)($_SERVER['CONTENT_LENGTH'] ?? 0) > 4096) { http_response_code(413); exit; }
        $data = json_decode(file_get_contents('php://input', false, null, 0, 4097), true);
        if (!is_array($data) || !is_string($data['client_id'] ?? null) || !is_string($data['attempt_id'] ?? '') || !preg_match('/^[A-Za-z0-9_-]{16,100}$/D', (string)($data['client_id'] ?? '')) ||
            !isset($data['sequence']) || !is_int($data['sequence']) || $data['sequence'] < 0 ||
            !in_array($data['state'] ?? '', ['off', 'connecting', 'live'], true) ||
            !in_array($data['source'] ?? 'web', ['web', 'android'], true) ||
            !preg_match('/^[A-Za-z0-9_-]{0,100}$/D', (string)($data['attempt_id'] ?? '')) ||
            !is_string($data['genre'] ?? '') || strlen($data['genre'] ?? '') > 200 ||
            !is_string($data['genre_label'] ?? '') || strlen($data['genre_label'] ?? '') > 240) {
            http_response_code(400); echo '{"error":"invalid_activity"}'; exit;
        }
        echo json_encode(fm_activity($data, null, ($data['source'] ?? 'web') === 'android' ? hash('sha256', $data['client_id']) : fm_tracker_id()), JSON_THROW_ON_ERROR);
    } else { header('Allow: GET, POST'); http_response_code(405); }
} catch (Throwable $e) {
    error_log('24FM activity: ' . $e->getMessage());
    http_response_code(503); echo '{"error":"unavailable"}';
}
