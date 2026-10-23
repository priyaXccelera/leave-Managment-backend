INSERT INTO leave_types (name, code, yearly_quota, unlimited, paid, carry_forward_allowed, max_carry_forward_days,
                         half_day_allowed, attachment_required, attachment_required_after_days, min_notice_days, active)
VALUES ('Casual Leave',        'CL',  12,  FALSE, TRUE,  FALSE, 0,  TRUE,  FALSE, NULL, 0, TRUE),
       ('Sick Leave',          'SL',  10,  FALSE, TRUE,  FALSE, 0,  TRUE,  TRUE,  2,    0, TRUE),
       ('Earned Leave',        'EL',  15,  FALSE, TRUE,  TRUE,  30, TRUE,  FALSE, NULL, 2, TRUE),
       ('Maternity Leave',     'ML',  182, FALSE, TRUE,  FALSE, 0,  FALSE, FALSE, NULL, 0, TRUE),
       ('Paternity Leave',     'PL',  10,  FALSE, TRUE,  FALSE, 0,  FALSE, FALSE, NULL, 0, TRUE),
       ('Compensatory Off',    'CO',  0,   FALSE, TRUE,  FALSE, 0,  TRUE,  FALSE, NULL, 0, TRUE),
       ('Loss of Pay',         'LOP', 0,   TRUE,  FALSE, FALSE, 0,  TRUE,  FALSE, NULL, 0, TRUE)
ON CONFLICT (code) DO NOTHING;
