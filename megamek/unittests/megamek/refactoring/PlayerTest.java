package megamek.refactoring;

import megamek.common.Player;
import megamek.server.rating.PlayerRating;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class PlayerTest {
    @Test
    public void testGetRating() throws NoSuchFieldException, IllegalAccessException {
        // Création d'un mock de PlayerRating
        PlayerRating mockRating = mock(PlayerRating.class);

        // Définition du comportement attendu du mock
        double expectedRating = 1500;
        when(mockRating.getRating()).thenReturn(expectedRating);

        // Création d'un joueur
        Player player = new Player(1, "John Doe");

        // Accès à la variable rating en utilisant la réflexion
        Field ratingField = Player.class.getDeclaredField("rating");
        ratingField.setAccessible(true);
        ratingField.set(player, mockRating);

        // Appel de la méthode getRating()
        double actualRating = player.getRating().getRating();

        // Vérification du résultat
        assertEquals(expectedRating, actualRating, "Le rating retourné devrait être celui du mock");
    }
}
