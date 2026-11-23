package io.github.zerohomers0.onesteplite;

/** A real read-capable SystemUI drop may delegate only within its original user. */
final class DropDelegationPolicy {
    private DropDelegationPolicy(){}
    static boolean allowed(int caller,int host,int sourceUser,int recipient,boolean read,boolean active,boolean hostDrop) {
        return caller>=0 && caller==host && sourceUser>=0 && sourceUser==caller/100000
                && recipient>=0 && recipient/100000==sourceUser && read && active && hostDrop;
    }
}
