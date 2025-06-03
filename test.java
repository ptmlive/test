@RequestScope
@Component
public class AuthenticatedUser {

    /**
     * Pobiera aktualne uwierzytelnienie z SecurityContextHolder
     * i zwraca wartość claimu "employeeId" z tokena JWT.
     * Zwraca null, jeżeli nie ma uwierzytelnionego użytkownika
     * lub nie ma właściwego pola w tokenie.
     */
    public String getUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return null;
        }

        Object principal = authentication.getPrincipal();
        if (!(principal instanceof Jwt)) {
            return null;
        }

        Jwt jwt = (Jwt) principal;
        // Zakładamy, że w JWT jest pole "employeeId"
        Object claim = jwt.getClaim("employeeId");
        return (claim != null) ? claim.toString() : null;
    }
}
