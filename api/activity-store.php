<?php
declare(strict_types=1);

function fm_data_dir(): string {
    $dir = getenv('FM24_DATA_DIR') ?: dirname(__DIR__) . '/data';
    if (!is_dir($dir) && !mkdir($dir, 0750, true) && !is_dir($dir)) throw new RuntimeException('Cannot create activity storage');
    return $dir;
}

function fm_tracker_id(): string {
    if (session_status() !== PHP_SESSION_ACTIVE) {
        session_name('24fm_tracker');
        session_set_cookie_params(['path' => '/', 'httponly' => true,
            'secure' => !empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off', 'samesite' => 'Lax']);
        session_start();
    }
    if (!isset($_SESSION['24fm_start'])) $_SESSION['24fm_start'] = time();
    $id = hash('sha256', session_id());
    session_write_close();
    return $id;
}

function fm_append_event(array $entry): void {
    $fp = fopen(fm_data_dir() . '/events.jsonl', 'ab');
    if (!$fp) throw new RuntimeException('Cannot open event storage');
    try {
        if (!flock($fp, LOCK_EX)) throw new RuntimeException('Cannot lock event storage');
        $line = json_encode($entry, JSON_UNESCAPED_UNICODE | JSON_THROW_ON_ERROR) . "\n";
        if (fwrite($fp, $line) !== strlen($line) || !fflush($fp)) throw new RuntimeException('Cannot save event');
    } finally { flock($fp, LOCK_UN); fclose($fp); }
}

function fm_event(string $event, string $genre = '', array $extra = [], ?int $now = null): void {
    $now ??= time();
    $genre = implode('', array_slice(preg_split('//u', $genre, -1, PREG_SPLIT_NO_EMPTY) ?: [], 0, 80));
    fm_append_event(array_merge([
        'ts' => gmdate('c', $now), 'event' => $event, 'genre' => $genre,
        'ip' => substr($_SERVER['REMOTE_ADDR'] ?? 'unknown', 0, 45),
        'ua' => substr($_SERVER['HTTP_USER_AGENT'] ?? '', 0, 255), 'duration' => 0,
    ], $extra));
}

/* Shared locked presence storage. Time injection is for isolated tests, never HTTP input. */
function fm_activity(?array $input = null, ?int $now = null, string $visitor = ''): array {
    $now ??= time();
    $dir = fm_data_dir();
    $lock = fopen($dir . '/activity.lock', 'c');
    if (!$lock) throw new RuntimeException('Cannot open activity lock');
    try {
        if (!flock($lock, LOCK_EX)) throw new RuntimeException('Cannot lock activity');
        $file = $dir . '/activity.json';
        $state = is_file($file) ? json_decode((string)file_get_contents($file), true, 512, JSON_THROW_ON_ERROR) : [];
        $clients = $state['clients'] ?? [];
        $confirmed = $state['confirmed'] ?? [];
        foreach ($clients as $id => $client) {
            if (($client['seen_at'] ?? 0) <= $now - 90) unset($clients[$id]);
        }
        foreach ($confirmed as $id => $ts) if ($ts <= $now - 86400) unset($confirmed[$id]);
        if ($input !== null) {
            $id = hash('sha256', $input['client_id']);
            $old = $clients[$id] ?? [];
            $sequence = (int)$input['sequence'];
            if ($sequence > ($old['sequence'] ?? -1)) {
                $closed = !empty($input['closed']);
                $native = ($input['source'] ?? 'web') === 'android';
                $live = !$closed && ($input['state'] ?? 'off') === 'live';
                $clients[$id] = ['sequence' => $sequence, 'visitor' => $visitor,
                    'visitor_at' => !$closed && !$native ? $now : 0,
                    'listener_at' => $live ? $now : 0, 'seen_at' => $now];
                $attempt = (string)($input['attempt_id'] ?? '');
                if ($live && $attempt !== '') {
                    $key = hash('sha256', $id . ':' . $attempt);
                    if (!isset($confirmed[$key])) {
                        fm_event('play_confirmed', (string)($input['genre'] ?? ''),
                            ['playback_id' => $key, 'visitor_id' => $visitor, 'genre_label' => $input['genre_label'] ?? '', 'platform' => $native ? 'android' : 'web'], $now);
                        $confirmed[$key] = $now;
                    }
                }
            }
        }
        $visitors = [];
        $listeners = 0;
        foreach ($clients as $client) {
            if (($client['visitor_at'] ?? 0) > $now - 90) $visitors[$client['visitor']] = true;
            if (($client['listener_at'] ?? 0) > $now - 90) $listeners++;
        }
        $encoded = json_encode(['clients' => $clients, 'confirmed' => $confirmed], JSON_THROW_ON_ERROR);
        $temp = tempnam($dir, '.activity-');
        if ($temp === false) throw new RuntimeException('Cannot create activity file');
        try {
            if (file_put_contents($temp, $encoded) !== strlen($encoded) || !rename($temp, $file)) throw new RuntimeException('Cannot save activity');
        } finally { if (is_file($temp)) unlink($temp); }
        $result = ['live_users' => count($visitors), 'listeners' => $listeners];
        if ($input !== null) $result['self_listener'] = ($clients[hash('sha256', $input['client_id'])]['listener_at'] ?? 0) > $now - 90;
        return $result;
    } finally { flock($lock, LOCK_UN); fclose($lock); }
}
