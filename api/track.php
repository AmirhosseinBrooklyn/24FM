<?php
declare(strict_types=1);
require_once __DIR__ . '/activity-store.php';
header('Cache-Control: no-store');
if ($_SERVER['REQUEST_METHOD'] !== 'POST') { header('Allow: POST'); http_response_code(405); exit; }
if ((int)($_SERVER['CONTENT_LENGTH'] ?? 0) > 4096) { http_response_code(413); exit; }
$data = json_decode(file_get_contents('php://input', false, null, 0, 4097), true);
if (!is_array($data) || !is_string($data['genre'] ?? '') || !is_string($data['message'] ?? '') || !is_string($data['source'] ?? '') || !in_array($data['event'] ?? '', ['visit', 'genre_select', 'stop', 'fe_error'], true)) {
    http_response_code(400); exit;
}
try {
    $extra = ['visitor_id' => fm_tracker_id()];
    if ($data['event'] === 'fe_error') {
        $extra['message'] = substr((string)($data['message'] ?? ''), 0, 500);
        $extra['source'] = substr((string)($data['source'] ?? ''), 0, 100);
        $message = preg_replace('/[\r\n]+/', ' ', $extra['source'] . ': ' . $extra['message']);
        file_put_contents(fm_data_dir() . '/debug.log', gmdate('c') . ' [ERROR] [frontend] ' . $message . PHP_EOL, FILE_APPEND | LOCK_EX);
    }
    fm_event($data['event'], (string)($data['genre'] ?? ''), $extra);
    http_response_code(204);
} catch (Throwable $e) { error_log('24FM tracking: ' . $e->getMessage()); http_response_code(503); }
