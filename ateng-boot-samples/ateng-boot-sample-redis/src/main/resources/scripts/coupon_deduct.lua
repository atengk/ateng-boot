-- scripts/coupon_deduct.lua
-- 优惠券原子库存扣减 Lua 脚本
-- KEYS[1]: 优惠券库存键 (如 sample:coupon:stock:{couponId})
-- ARGV[1]: 扣减数量 (通常为正整数)
local key = KEYS[1]
local deduct = tonumber(ARGV[1])
local raw = redis.call('get', key)
local current = 0
if raw then
    if type(raw) == 'string' then
        raw = string.gsub(raw, '^"*(.-)"*$', '%1')
    end
    current = tonumber(raw) or 0
end

if current >= deduct then
    local remaining = current - deduct
    redis.call('set', key, tostring(remaining))
    return remaining
else
    return -1
end
