package com.logicgate.farm.service;

import com.logicgate.farm.domain.Animal;
import com.logicgate.farm.domain.Barn;
import com.logicgate.farm.domain.Color;
import com.logicgate.farm.repository.AnimalRepository;
import com.logicgate.farm.repository.BarnRepository;

import com.logicgate.farm.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.Map.*;
import java.util.stream.*;

@Service
@Transactional
public class AnimalServiceImpl implements AnimalService {

    private final AnimalRepository animalRepository;

    private final BarnRepository barnRepository;

    @Autowired
    public AnimalServiceImpl(AnimalRepository animalRepository, BarnRepository barnRepository) {
        this.animalRepository = animalRepository;
        this.barnRepository = barnRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Animal> findAll() {
        return animalRepository.findAll();
    }

    @Override
    public void deleteAll() {
        animalRepository.deleteAll();
    }

    @Override
    @Transactional
    public Animal addToFarm(Animal animal) {
        Color color = animal.getFavoriteColor();
        List<Barn> barnsByColor = barnRepository.findAllByColor(color);

        if (barnsByColor.isEmpty()) {
            Barn newBarn = createBarn(color);
            animal.setBarn(newBarn);
            barnRepository.save(newBarn);
            return animalRepository.save(animal);
        }

        Map<Barn, List<Animal>> barnAnimalMap = findAll()
                .stream()
                .filter(persistedAnimal -> color == persistedAnimal.getFavoriteColor())
                .collect(Collectors.groupingBy(Animal::getBarn));

        Map<Barn, Integer> unusedCapacity = barnAnimalMap
                .entrySet()
                .stream()
                .collect(Collectors.toMap(Entry::getKey, barn -> barn.getValue().size()));

        Entry<Barn, Integer> highestVacancyBarn = Collections
                .min(unusedCapacity.entrySet(), Comparator.comparingInt(Entry::getValue));

        List<Animal> currentColorAnimals = findAll()
                .stream()
                .filter(filteredAnimal -> filteredAnimal.getFavoriteColor() == color)
                .collect(Collectors.toList());

        if (highestVacancyBarn.getValue() < FarmUtils.barnCapacity()) {
            animal.setBarn(highestVacancyBarn.getKey());
            return animalRepository.save(animal);
        } else {
            //Create a new barn add it to the list of barns
            barnsByColor.add(createBarn(color));

            currentColorAnimals.add(animal);

            int minBarnCapacity = currentColorAnimals.size() / barnsByColor.size();

            balanceBarns(barnsByColor, currentColorAnimals, minBarnCapacity);

            return animalRepository.save(animal);
        }
    }

    private Barn createBarn(Color color) {
        return new Barn(FarmUtils.barnName(new Random().nextInt(1000)), color);
    }

    @Override
    public void addToFarm(List<Animal> animals) {
        animals.forEach(this::addToFarm);
    }

    @Override
    @Transactional
    public void removeFromFarm(Animal animal) {
        Map<Barn, List<Animal>> barnAnimalMap = findAll()
                .stream()
                .filter(persistedAnimal -> animal.getFavoriteColor() == persistedAnimal.getFavoriteColor())
                .collect(Collectors.groupingBy(Animal::getBarn));

        //Only one barn
        if (barnAnimalMap.size() == 1) {
            List<Animal> currentAnimals = barnAnimalMap.entrySet()
                                                       .iterator()
                                                       .next()
                                                       .getValue();

            //when only 1 animal remaining the barn delete barn and animal
            if (currentAnimals.size() == 1) {
                barnRepository.delete(animal.getBarn());
                animalRepository.delete(animal);
            } else if (currentAnimals.size() > 1) {
                //only delete animal
                animalRepository.delete(animal);
            } else {
                //how did I get here?
                animalRepository.delete(animal);
            }
            return;
        }
        //more than 1 barn might have to rebalance
        //check if you should delete the barn
        List<Animal> animalsInBarn = barnAnimalMap.values()
                                                  .stream()
                                                  .flatMap(List::stream)
                                                  .collect(Collectors.toList());
        animalsInBarn.remove(animal);

        List<Barn> matchingBarns = new ArrayList<>(barnAnimalMap.keySet());
        int minBarnCapacity = animalsInBarn.size() / (matchingBarns.size() - 1);
        int minBarnRemainder = animalsInBarn.size() % (matchingBarns.size() - 1);
        int maxBarnCapacity = animalsInBarn.size() / matchingBarns.size();
        Barn barnToRemove = matchingBarns.get(0);

        //check for remainder in case the barn capacity is 20 if so always go with maxBarns
        if (minBarnCapacity <= FarmUtils.barnCapacity() && minBarnRemainder == 0) {
            matchingBarns.remove(barnToRemove);
            balanceBarns(matchingBarns, animalsInBarn, minBarnCapacity);
            barnRepository.delete(barnToRemove);
        } else if (maxBarnCapacity <= FarmUtils.barnCapacity() && maxBarnCapacity > 1) {
            balanceBarns(matchingBarns, animalsInBarn, maxBarnCapacity);
        }

        animalRepository.delete(animal);
    }

    @Override
    public void removeFromFarm(List<Animal> animals) {
        animals.forEach(animal -> removeFromFarm(animalRepository.getOne(animal.getId())));
    }

    private List<List<Animal>> groupAnimals(List<Animal> currentColorAnimals, Integer groupSize) {
        List<List<Animal>> groupedAnimals = new ArrayList<>();

        int startOfGroup = 0;
        while (startOfGroup <= currentColorAnimals.size()) {
            int endOfGroup = ((startOfGroup + groupSize) < currentColorAnimals
                    .size()) ? startOfGroup + groupSize : currentColorAnimals.size();
            groupedAnimals.add(new ArrayList<>(currentColorAnimals.subList(startOfGroup, endOfGroup)));
            startOfGroup = startOfGroup + groupSize;
        }
        return groupedAnimals;
    }

    private void balanceBarns(List<Barn> barnsByColor, List<Animal> currentColorAnimals, int minBarnCapacity) {
        //Group the animals by defined capacity
        List<List<Animal>> groupedAnimals = groupAnimals(currentColorAnimals, minBarnCapacity);

        //if there is a remainder add them to the other barns
        if (groupedAnimals.size() > barnsByColor.size()) {
            int i = 0;
            List<Animal> leftOverAnimals = groupedAnimals.get(groupedAnimals.size() - 1); //remainder group
            while (!leftOverAnimals.isEmpty()) {
                List<Animal> currentGroup = groupedAnimals.get(i);
                i++;
                Animal currentAnimal = leftOverAnimals.get(0);
                currentGroup.add(currentAnimal);
                leftOverAnimals.remove(currentAnimal);
            }
            groupedAnimals.remove(leftOverAnimals);
        }
        //loop through and set the new barn
        for (int i = 0; i < groupedAnimals.size(); i++) {
            List<Animal> animalsToBarn = groupedAnimals.get(i);
            for (Animal barnAnimal : animalsToBarn) {
                barnAnimal.setBarn(barnsByColor.get(i));
            }
        }

        barnRepository.saveAll(barnsByColor);
        animalRepository.saveAll(currentColorAnimals);
    }
}
