<?php
declare(strict_types=1);

if ($_SERVER['REQUEST_METHOD'] !== 'POST') { http_response_code(405); exit; }

$raw = file_get_contents('php://input');
if (!$raw) { http_response_code(400); exit; }

$data = json_decode($raw, true);
if (!is_array($data)) { http_response_code(400); exit; }

$allowed_events = ['visit', 'play', 'genre_select', 'stop', 'fe_error'];
$event = isset($data['event']) ? (string)$data['event'] : '';
if (!in_array($event, $allowed_events, true)) { http_response_code(400); exit; }

$genre = isset($data['genre'])
    ? substr(htmlspecialchars((string)$data['genre'], ENT_QUOTES, 'UTF-8'), 0, 40) : '';

$ip = $_SERVER['HTTP_X_FORWARDED_FOR'] ?? $_SERVER['REMOTE_ADDR'] ?? 'unknown';
$ip = substr(htmlspecialchars(trim(explode(',', $ip)[0]), ENT_QUOTES, 'UTF-8'), 0, 45);
$ua = substr(htmlspecialchars($_SERVER['HTTP_USER_AGENT'] ?? '', ENT_QUOTES, 'UTF-8'), 0, 255);

$log_dir  = dirname(__DIR__) . '/data';
$log_file = $log_dir . '/events.jsonl';

if (!is_dir($log_dir)) {
    @mkdir($log_dir, 0755, true);
    if (!file_exists($log_dir . '/.htaccess')) {
        @file_put_contents($log_dir . '/.htaccess', "Order allow,deny\nDeny from all\n");
    }
}

if ($event === 'fe_error') {
    $msg = substr(htmlspecialchars((string)($data['message'] ?? ''), ENT_QUOTES, 'UTF-8'), 0, 500);
    $src = substr(htmlspecialchars((string)($data['source']  ?? ''), ENT_QUOTES, 'UTF-8'), 0, 100);
    $debug_file = $log_dir . '/debug.log';
    $line = date('Y-m-d H:i:s') . ' [ERROR] [frontend] ' . $src . ': ' . $msg . ' | IP:' . $ip . PHP_EOL;
    $fp = @fopen($debug_file, 'a');
    if ($fp) { flock($fp, LOCK_EX); fwrite($fp, $line); flock($fp, LOCK_UN); fclose($fp); }
    http_response_code(204); exit;
}

if (session_status() === PHP_SESSION_NONE) {
    session_name('24fm_tracker');
    @session_start();
}
if (empty($_SESSION['24fm_start'])) $_SESSION['24fm_start'] = time();
$duration = time() - (int)$_SESSION['24fm_start'];

$entry = json_encode([
    'ts'       => date('Y-m-d H:i:s'),
    'event'    => $event,
    'genre'    => $genre,
    'ip'       => $ip,
    'ua'       => $ua,
    'duration' => $duration,
], JSON_UNESCAPED_UNICODE);

$ok = false;
$fp = @fopen($log_file, 'a');
if ($fp) {
    flock($fp, LOCK_EX);
    fwrite($fp, $entry . "\n");
    flock($fp, LOCK_UN);
    fclose($fp);
    $ok = true;
}

if (!$ok) {
    $debug_file = $log_dir . '/debug.log';
    $reason = is_dir($log_dir) ? (is_writable($log_dir) ? 'fopen failed' : 'dir not writable') : 'dir missing';
    $line = date('Y-m-d H:i:s') . ' [ERROR] [track.php] Cannot write events.jsonl — ' . $reason . ' | event:' . $event . PHP_EOL;
    $fp2 = @fopen($debug_file, 'a');
    if ($fp2) { flock($fp2, LOCK_EX); fwrite($fp2, $line); flock($fp2, LOCK_UN); fclose($fp2); }
}

http_response_code(204);
exit;
