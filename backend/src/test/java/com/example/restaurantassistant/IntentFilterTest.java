package com.example.restaurantassistant;
import com.example.restaurantassistant.dto.Contracts.*;
import com.example.restaurantassistant.entity.UserPreference;
import com.example.restaurantassistant.exception.LlmException;
import com.example.restaurantassistant.model.*;
import com.example.restaurantassistant.service.menu.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
class IntentFilterTest {
  MenuService menu;
  IntentFilter filter;
  IntentFilterTest() throws Exception { menu=new MenuService(JsonMapper.builder().build());filter=new IntentFilter(menu); }
  Intent intent(List<String> foods,List<String> ingredients,List<String> exclusions,String budget,String spice) {
    return new Intent(foods,ingredients,List.of(),exclusions,List.of(),List.of(),budget,spice);
  }
  @Test void chickenCravingCannotBecomePizzaAndSpiceIsEnforced() {
    var result=filter.filter(menu.all(),intent(List.of(),List.of("chicken"),List.of(),"15","spicy"));
    assertThat(result).extracting(MenuItem::id).containsExactly("jalapeno-chicken-burger","spicy-chicken-bowl");
  }
  @Test void categoryAndIngredientAreCombined() {
    assertThat(filter.filter(menu.all(),intent(List.of("bowls"),List.of("chicken"),List.of(),null,null)))
        .extracting(MenuItem::id).containsExactly("spicy-chicken-bowl");
  }
  @Test void exclusionsAndWordBoundaries() {
    assertThat(filter.filter(menu.all(),intent(List.of(),List.of(),List.of("mushroom"),null,null)))
        .noneMatch(m->m.ingredients().contains("mushroom"));
    assertThat(filter.filter(menu.all(),intent(List.of(),List.of("chick"),List.of(),null,null))).isEmpty();
  }
  @Test void missingFoodHasNoSubstitute() {
    assertThat(filter.filter(menu.all(),intent(List.of("sushi"),List.of(),List.of(),null,null))).isEmpty();
  }
  @Test void savedBudgetAllergyAndDietStillWin() {
    var budget=new UserPreference();budget.type=PreferenceType.BUDGET_MAX;budget.value="12";
    var allergy=new UserPreference();allergy.type=PreferenceType.ALLERGY;allergy.value="peanut";
    var diet=new UserPreference();diet.type=PreferenceType.DIETARY_RESTRICTION;diet.value="vegan";
    assertThat(filter.filter(menu.eligible(List.of(budget,allergy,diet)),Intent.empty()))
        .allMatch(m->m.vegan() && m.price().intValue()<=12 && !m.allergens().contains("peanut"));
    assertThat(filter.filter(menu.eligible(List.of(budget)),intent(List.of(),List.of(),List.of(),"100",null)))
        .allMatch(m->m.price().intValue()<=12);
  }
  @Test void malformedIntentFailsClosed() {
    assertThatThrownBy(()->filter.filter(menu.all(),new Intent(null,List.of(),List.of(),List.of(),List.of(),List.of(),null,null))).isInstanceOf(LlmException.class);
    assertThatThrownBy(()->filter.filter(menu.all(),intent(List.of(),List.of(),List.of(),"banana",null))).isInstanceOf(LlmException.class);
  }
}
