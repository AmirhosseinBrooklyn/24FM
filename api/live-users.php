<?php
/* ============================================================
   api/live-users.php — Active user count (session-based)
   ============================================================ */

declare(strict_types=1);

/* Only admin panel should call this — restrict referrer or use token */
header('Content-Type: application/json');
header('Cache-Control: no-store');

/* Session heartbeat: each visitor pings every 30s; expire after 90s */
$sessions_dir = sys_get_temp_dir() . '/24fm_sessions';
if (!is_dir($sessions_dir)) mkdir($sessions_dir, 0700, true);

/* Clean up stale sessions (older than 90 seconds) */
$now = time();
foreach (glob($sessions_dir . '/*.sess') ?: [] as $f) {
    if (($now - filemtime($f)) > 90) @unlink($f);
}

/* Touch this session's heartbeat file */
$session_id = session_id() ?: (isset($_COOKIE['PHPSESSID']) ? preg_replace('/[^a-zA-Z0-9]/', '', $_COOKIE['PHPSESSID']) : '');
if ($session_id) {
    $sess_file = $sessions_dir . '/' . $session_id . '.sess';
    touch($sess_file);
}

/* Count active sessions */
$count = count(glob($sessions_dir . '/*.sess') ?: []);

echo json_encode(['live_users' => $count]);
exit;
