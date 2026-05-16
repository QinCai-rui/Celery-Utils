package xyz.qincai.celeryutils.modules;

/**
 * Represents a rule for economy-based permissions.
 * This is an immutable record.
 */
public record EconomyPermissionRule(
        double minBalance,
        String permissionNode,
        boolean revokeOnBalanceBelow,
        boolean autoGrant,
        boolean buyable,
        double price,
        long durationSeconds
) {
    public EconomyPermissionRule withPrice(double newPrice) {
        return new EconomyPermissionRule(minBalance, permissionNode, revokeOnBalanceBelow, autoGrant, buyable, newPrice, durationSeconds);
    }

    public EconomyPermissionRule withDuration(long newDuration) {
        return new EconomyPermissionRule(minBalance, permissionNode, revokeOnBalanceBelow, autoGrant, buyable, price, newDuration);
    }
}
