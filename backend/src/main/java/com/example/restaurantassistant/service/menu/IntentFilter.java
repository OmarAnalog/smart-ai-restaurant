package com.example.restaurantassistant.service.menu;
import com.example.restaurantassistant.dto.Contracts.*;
import com.example.restaurantassistant.entity.UserPreference;
import com.example.restaurantassistant.exception.LlmException;
import com.example.restaurantassistant.model.*;
import com.example.restaurantassistant.service.PreferenceService;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
@Service
public class IntentFilter {
  private final MenuService menu;
  public IntentFilter(MenuService menu) { this.menu=menu; }
  public List<MenuItem> filter(List<MenuItem> candidates, Intent intent) {
    validate(intent);
    var transientFacts=new ArrayList<UserPreference>();
    intent.allergies().forEach(v->transientFacts.add(fact(PreferenceType.ALLERGY,v)));
    intent.diets().forEach(v->transientFacts.add(fact(PreferenceType.DIETARY_RESTRICTION,v)));
    if (intent.budgetMax()!=null) transientFacts.add(fact(PreferenceType.BUDGET_MAX,intent.budgetMax()));
    var allowed=menu.eligible(transientFacts).stream().map(MenuItem::id).collect(java.util.stream.Collectors.toSet());
    return candidates.stream().filter(m->allowed.contains(m.id()))
        .filter(m->intent.foods().isEmpty() || intent.foods().stream().anyMatch(v->matches(foodFacts(m),v)))
        .filter(m->intent.ingredients().stream().allMatch(v->matches(String.join(" ",m.ingredients()),v)))
        .filter(m->intent.excludedFoods().stream().noneMatch(v->matches(foodFacts(m),v)))
        .filter(m->intent.excludedIngredients().stream().noneMatch(v->matches(String.join(" ",m.ingredients())+" "+String.join(" ",m.allergens()),v)))
        .filter(m->intent.spice()==null || switch(intent.spice()) {
          case "mild" -> m.spiceLevel()<=1;
          case "medium" -> m.spiceLevel()>=2 && m.spiceLevel()<=3;
          case "spicy" -> m.spiceLevel()>=4;
          default -> false;
        }).toList();
  }
  public static void validate(Intent intent) {
    try {
      if (intent==null) throw new IllegalArgumentException();
      for (var list:Arrays.asList(intent.foods(),intent.ingredients(),intent.excludedFoods(),intent.excludedIngredients(),intent.allergies(),intent.diets())) {
        if (list==null || list.size()>10 || list.stream().anyMatch(v->v==null || v.isBlank() || v.length()>100)) throw new IllegalArgumentException();
      }
      if(intent.spice()!=null && !Set.of("mild","medium","spicy").contains(intent.spice())) throw new IllegalArgumentException();
      if(intent.budgetMax()!=null) PreferenceService.validate(List.of(new Update(Operation.UPSERT,PreferenceType.BUDGET_MAX,intent.budgetMax(),1)));
    } catch(IllegalArgumentException e) { throw new LlmException("AI returned invalid current-request constraints.",e); }
  }
  private static UserPreference fact(PreferenceType type,String value) { var p=new UserPreference();p.type=type;p.value=value;return p; }
  private static String foodFacts(MenuItem m) { return m.name()+" "+m.category()+" "+String.join(" ",m.ingredients()); }
  private static String normalized(String value) {
    return MenuService.canonical(value)
        .replace("burgers","burger").replace("bowls","bowl").replace("salads","salad").replace("pizzas","pizza");
  }
  private static boolean matches(String facts,String term) {
    return Pattern.compile("(?<![\\p{L}\\p{N}])"+Pattern.quote(normalized(term))+"(?![\\p{L}\\p{N}])").matcher(normalized(facts)).find();
  }
}
