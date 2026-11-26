UPDATE drafts SET state='outdated'
WHERE state='waiting_human' AND message_id IN
 (SELECT id FROM messages WHERE message_type='system_hint');
