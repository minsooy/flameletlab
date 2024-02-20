package com.teamvalkyrie.flameletlab.flameletlabapi.service;

import com.teamvalkyrie.flameletlab.flameletlabapi.constant.Animals;
import com.teamvalkyrie.flameletlab.flameletlabapi.model.*;
import com.teamvalkyrie.flameletlab.flameletlabapi.model.misc.Animal;
import com.teamvalkyrie.flameletlab.flameletlabapi.repository.*;
import com.teamvalkyrie.flameletlab.flameletlabapi.service.dto.CreateGroupRequest;
import com.teamvalkyrie.flameletlab.flameletlabapi.service.dto.WebSocketMessage;
import com.teamvalkyrie.flameletlab.flameletlabapi.service.exception.GroupChatException;
import lombok.AllArgsConstructor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

@Transactional(readOnly = true)
@Service
@AllArgsConstructor
public class ChatService {

    private final GroupChatRepository groupChatRepository;
    private final OccupationTypeRepository occupationTypeRepository;

    private final ChatTagRepository chatTagRepository;

    private final UserService userService;

    private final AnonymousGroupChatUserRepository anonymousGroupChatUserRepository;

    private final GroupChatMessageRepository groupChatMessageRepository;

    /**
     * Gets a single group chat with its messages
     *
     * @param id the group chat id
     * @return an optional containing the group chat and messages or empty
     */
    public Optional<GroupChat> getGroupByIdWithMessages(Long id) {
        return groupChatRepository.findOneByIdWithMessages(id);
    }

    /**
     * Creates new tags that the user has specified
     *
     * @param tags the tags containing new tags if any
     * @return new chat tags
     */
    @Transactional
    public Set<ChatTag> createNewTags(List<String> tags) {
        List<ChatTag> allTags = chatTagRepository.findAll();
        Set<ChatTag> persistedTags = new HashSet<>();

        tags.forEach(t -> {
            if (allTags.stream().map(ChatTag::getName).noneMatch(ct -> ct.equalsIgnoreCase(t))) {
                ChatTag newChatTag = new ChatTag();
                newChatTag.setName(t.toLowerCase());
                persistedTags.add(chatTagRepository.save(newChatTag));
            } else {
                persistedTags.add(allTags.stream().filter(ct -> ct.getName().equalsIgnoreCase(t)).findFirst().get());
            }
        });


        return persistedTags;
    }

    /**
     * Creates a new group chat
     * @param request group details
     */
    @Transactional
    public GroupChat createGroup(CreateGroupRequest request) {
        GroupChat newGroup = new GroupChat();
        newGroup.setName(request.getName());
        newGroup.setCreated(ZonedDateTime.now());
        Optional<OccupationType> occupationType = occupationTypeRepository.findById(request.getOccupationTypeId());
        if(occupationType.isEmpty()) {
            throw new GroupChatException("Unable to create group, occupation type is wrong");
        }
        newGroup.setTotalUsers(0);
        newGroup.setOccupationType(occupationType.get());
        newGroup.setTags(createNewTags(request.getTags()));



        var newSavedGroup =  groupChatRepository.save(newGroup);
        // Join owner of group
        joinGroup(newSavedGroup.getId());

        return newSavedGroup;
     }


    /**
     * Retrieves a random animal
     * @return the random animal
     */
     private Animal getRandomAnimal() {
         Random rand = new Random();
         int randomAnimalIndex = rand.nextInt(Animals.getAnimals().size());
         return Animals.getAnimals().get(randomAnimalIndex);
     }

     public AnonymousGroupChatUser getNewAnonymousUserForGroup(Long groupChatId) {

        var groupChatOptional = groupChatRepository.findOneByIdWithUsers(groupChatId);
        if (groupChatOptional.isPresent()) {
           var users = groupChatOptional.get().getAnonymousUsers();

            /*
             * @TODO there may be a case where all anonymous user names
             * are taken in that case a hash can be appended such as
             * Anonymous Donkey #2
             *
             * This can be done a in a future release
             */
             AnonymousGroupChatUser newUser = null;
             int tried = 0;
             while(newUser == null && tried < Animals.getAnimals().size()) {
                 Animal animal = getRandomAnimal();
                 String newAnonymousName = "Anonymous " + animal.getName();
                 if (users.stream().map(AnonymousGroupChatUser::getAnonymousName)
                         .noneMatch(n -> n.equalsIgnoreCase(newAnonymousName))) {
                     newUser = new AnonymousGroupChatUser(null, userService.getCurrentLoggedInUser(),
                             groupChatOptional.get(), newAnonymousName, animal.getImage());
                 }
                 tried++;
           }

            return newUser;
        }

      return null;
     }

    /**
     * The current logged-in user is joining a group chat
     *
     * @param groupChatId the id of the group to join
     */
    @Transactional(rollbackFor = GroupChatException.class)
     public void joinGroup(Long groupChatId) {
        Optional<GroupChat> groupChatOptional = groupChatRepository.findById(groupChatId);
        GroupChat groupChat = null;
        if (groupChatOptional.isEmpty()) {
            throw new GroupChatException("Unable to find group");
        } else {
            groupChat = groupChatOptional.get();
        }
        var newAnonymousUser = getNewAnonymousUserForGroup(groupChatId);
        if (newAnonymousUser == null) {
            throw new GroupChatException("Unable to create anonymous name");
        }

        groupChat.setAnonymousUsers(Set.of(newAnonymousUser));
        groupChat.setTotalUsers(groupChat.getTotalUsers() + 1);
        anonymousGroupChatUserRepository.save(newAnonymousUser);
     }


    /**
     * Removes the current user from a specified group
     * @param groupChatId the group chat id to remove from
     */
     @Transactional
     public void leaveGroup(Long groupChatId) {
         Optional<GroupChat> groupChatOptional = groupChatRepository.findById(groupChatId);
         var anonUser = anonymousGroupChatUserRepository
                 .findAnonymousUserByUserIdAndGroupId(userService.getCurrentLoggedInUser().getId(), groupChatId);
         groupChatOptional.ifPresent(g -> {
            if (anonUser != null) {
                List<GroupChatMessage> userMessages = groupChatMessageRepository.
                        findAllByAnonymousUserId(anonUser.getId());
                groupChatMessageRepository.deleteAll(userMessages);
                anonymousGroupChatUserRepository.delete(anonUser);
                g.setTotalUsers(g.getTotalUsers() - 1);
                this.groupChatRepository.save(g);
            }
         });
     }

    /**
     * Gets all the groups based on a search criteria
     *
     * @param specification specification containing the search criteria
     * @return a list of group chats
     */
     public List<GroupChat> getAllGroupChats(Specification<GroupChat> specification) {
        return groupChatRepository.findAll(specification);
     }

    /**
     *
     * Get the anonymous user in a chat by groupId
     *
     * @param groupId the groupId
     * @return the anonymous group chat user that is in that groupChat
     */
     public AnonymousGroupChatUser getAnonymousUserNameByUserIdAndGroupId(Long userId, Long groupId) {
         return anonymousGroupChatUserRepository.findAnonymousUserByUserIdAndGroupId(userId, groupId);
     }

    /**
     * Saves a new message from web socket to the group for the current
     * logged in user
     *
     * @param groupId the group to save the message for
     * @param message the message to save
     */
     @Transactional(rollbackFor = GroupChatException.class)
     public GroupChatMessage saveMessageForGroup(Long groupId,WebSocketMessage message) {
         var currentUser = userService.findOneById(message.getUserId());
        if (currentUser.isPresent()) {
            var anonymousUser= this.getAnonymousUserNameByUserIdAndGroupId(currentUser.get().getId(), groupId);
            if (anonymousUser == null) {
                throw new GroupChatException("Doesn't seem like you are joined to this group");
            }

            var chatGroupOptional = groupChatRepository.findOneByIdWithMessages(groupId);
            GroupChatMessage newMessage = new GroupChatMessage();
            AtomicReference<GroupChatMessage> savedMesssage = new AtomicReference<>(null);
            chatGroupOptional.ifPresent(gc -> {
                newMessage.setMessage(message.getMessage());
                newMessage.setCreated(ZonedDateTime.now());
                newMessage.setGroupChat(gc);
                newMessage.setAnonymousUser(anonymousUser);
               savedMesssage.set(this.groupChatMessageRepository.saveAndFlush(newMessage));
            });

            return savedMesssage.get();

        }
        throw new GroupChatException("Unable to save message");
     }


}
