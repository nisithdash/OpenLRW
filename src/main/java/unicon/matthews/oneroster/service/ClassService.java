package unicon.matthews.oneroster.service;

import java.util.Collection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import unicon.matthews.oneroster.Class;
import unicon.matthews.oneroster.Enrollment;
import unicon.matthews.oneroster.LineItem;
import unicon.matthews.oneroster.exception.EnrollmentNotFoundException;
import unicon.matthews.oneroster.exception.LineItemNotFoundException;
import unicon.matthews.oneroster.service.repository.MongoClass;
import unicon.matthews.oneroster.service.repository.MongoClassRepository;

@Service
public class ClassService {
  
  private static Logger logger = LoggerFactory.getLogger(ClassService.class);
  
  private ExecutorService threadpool;
  private MongoClassRepository mongoClassRepository;
  private EnrollmentService enrollmentService;
  private LineItemService lineItemService;
  
  @Autowired
  public ClassService(MongoClassRepository mongoClassRepository,
      EnrollmentService enrollmentService,
      LineItemService lineItemService,
      ExecutorService threadpool) {
    this.mongoClassRepository = mongoClassRepository;
    this.enrollmentService = enrollmentService;
    this.lineItemService = lineItemService;
    this.threadpool = threadpool;
  }
  
  public Class findBySourcedId(final String tenantId, final String orgId, final String classSourcedId) {
    MongoClass mongoClass
      =  mongoClassRepository
        .findByTenantIdAndOrgIdAndClassSourcedId(tenantId, orgId, classSourcedId);
    
    if (mongoClass != null) {
      return mongoClass.getKlass();
    }
    
    return null;
  }
  
  public Collection<Class> findClassesForCourse(final String tenantId, final String orgId, 
      final String courseSourcedId) {
    Collection<MongoClass> mongoClasses 
      = mongoClassRepository.findByTenantIdAndOrgIdAndKlassCourseSourcedId(tenantId, orgId, courseSourcedId);
    
    if (mongoClasses != null && !mongoClasses.isEmpty()) {
      return mongoClasses.stream().map(MongoClass::getKlass).collect(Collectors.toList());
    }
    return null;
  }

  public Class save(final String tenantId, final String orgId, Class klass) {
    if (StringUtils.isBlank(tenantId) 
        || StringUtils.isBlank(orgId)
        || klass == null
        || StringUtils.isBlank(klass.getSourcedId())
        || StringUtils.isBlank(klass.getTitle())) {
      throw new IllegalArgumentException();
    }
    
    MongoClass mongoClass
      = mongoClassRepository
        .findByTenantIdAndOrgIdAndClassSourcedId(tenantId, orgId, klass.getSourcedId());
    
    
    if (mongoClass == null) {
      mongoClass 
        = new MongoClass.Builder()
          .withClassSourcedId(klass.getSourcedId())
          .withOrgId(orgId)
          .withTenantId(tenantId)
          .withKlass(klass)
          .build();
    }
    else {
      mongoClass
        = new MongoClass.Builder()
          .withId(mongoClass.getId())
          .withClassSourcedId(mongoClass.getClassSourcedId())
          .withOrgId(mongoClass.getOrgId())
          .withTenantId(mongoClass.getTenantId())
          .withKlass(klass)
          .build();
    }
    
    MongoClass saved = mongoClassRepository.save(mongoClass);
    
//    updateEnrollments(tenantId, orgId, mongoClass);
//    updateLineItems(tenantId, orgId, mongoClass);
    
    threadpool.submit(new ClassEnrollmentUpdater(enrollmentService, tenantId, orgId, mongoClass));
    
    return saved.getKlass(); 

  }
  
  @Async
  public void updateEnrollments(String tenantId, String orgId, MongoClass mongoClass) {
    try {
      Collection<Enrollment> classEnrollments = enrollmentService.findEnrollmentsForClass(tenantId, orgId, mongoClass.getClassSourcedId());
      
      if (classEnrollments != null) {
        for (Enrollment enrollment : classEnrollments) {
          Enrollment updatedEnrollment
            = new Enrollment.Builder()
              .withKlass(mongoClass.getKlass())
              .withMetadata(enrollment.getMetadata())
              .withPrimary(enrollment.isPrimary())
              .withRole(enrollment.getRole())
              .withSourcedId(enrollment.getSourcedId())
              .withStatus(enrollment.getStatus())
              .withUser(enrollment.getUser())
              .build();
          
          enrollmentService.save(tenantId, orgId, updatedEnrollment);
        }
      }
      
    } 
    catch (EnrollmentNotFoundException e) {
      logger.info("No enrollments found for class");
    }
  }
  
  @Async
  public void updateLineItems(String tenantId, String orgId, MongoClass mongoClass) {
    try {
      Collection<LineItem> classLineItems = lineItemService.getLineItemsForClass(tenantId, orgId, mongoClass.getClassSourcedId());
      
      if (classLineItems != null) {
        for (LineItem li : classLineItems) {
          LineItem updatedLineItem
            = new LineItem.Builder()
              .withAssignDate(li.getAssignDate())
              .withCategory(li.getCategory())
              .withClass(mongoClass.getKlass())
              .withDescription(li.getDescription())
              .withDueDate(li.getDueDate())
              .withMetadata(li.getMetadata())
              .withSourcedId(li.getSourcedId())
              .withStatus(li.getStatus())
              .withTitle(li.getTitle())
              .build();
          
          lineItemService.save(tenantId, orgId, updatedLineItem);
        }
      }
    } 
    catch (LineItemNotFoundException e) {
      logger.info("No line items found for class");
    }
  }
}
